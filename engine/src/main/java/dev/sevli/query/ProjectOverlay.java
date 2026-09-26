package dev.sevli.query;

import dev.sevli.env.AutoSync;
import dev.sevli.index.JarParser;
import dev.sevli.store.Db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code project:} qualifier: answers use the working directory's project build in place of the deployed copy of
 * its mod, so an agent sees where the project and pack mods meet before deploying. Opt-in; without it answers stay
 * the server's truth. A new build is indexed once (content-addressed, like any mod jar) by a separate short-lived
 * {@code sevli project index} process, because query and MCP processes never write to the index (ADR 0002).
 */
public final class ProjectOverlay {
    private static final Pattern TOKEN = Pattern.compile("(?:^|\\s)project:(\\S*)(?=\\s|$)");
    private static final long INDEX_TIMEOUT_S = 120;

    /** {@code modId} is what followed {@code project:} (optional; checked against the folder's project). */
    public record Parsed(String rest, boolean asked, String modId) {}

    private ProjectOverlay() {}

    public static Parsed extract(String query) {
        if (query == null) return new Parsed(null, false, null);
        Matcher m = TOKEN.matcher(query);
        if (!m.find()) return new Parsed(query, false, null);
        String id = m.group(1).isEmpty() ? null : m.group(1);
        return new Parsed((query.substring(0, m.start()) + " " + query.substring(m.end())).trim(), true, id);
    }

    public static Scope apply(QueryService q, Scope s, Path cwd, String expectedModId) throws SQLException, IOException {
        Project p = Project.find(q.config(), cwd);
        if (p == null) {
            throw new IllegalArgumentException("project: needs a Fabric mod project (Gradle build + src/main/resources/fabric.mod.json) at or above " + cwd);
        }
        if (expectedModId != null && !expectedModId.equalsIgnoreCase(p.modId())) {
            throw new IllegalArgumentException("project:" + expectedModId + " but the project here is " + p.modId() + " (" + p.dir() + ")");
        }
        if (p.jar() == null) throw new IllegalArgumentException("project " + p.modId() + " has no built jar in build/libs; build it first (gradlew build)");
        String sha = JarParser.sha256(Files.readAllBytes(p.jar()));
        Long id = indexed(q.db(), sha);
        if (id == null) {
            String log = index(q, s.env(), p.dir());
            id = indexed(q.db(), sha);
            if (id == null) throw new IllegalArgumentException("could not index " + p.jar().getFileName() + ": " + log);
        }

        Set<Long> added = new LinkedHashSet<>(List.of(id)); // with its jar-in-jar children
        List<Long> frontier = List.of(id);
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (long parent : frontier) {
                for (long child : q.db().query("SELECT child_id FROM artifact_nested WHERE parent_id=?", rs -> rs.getLong(1), parent)) {
                    if (added.add(child)) next.add(child);
                }
            }
            frontier = next;
        }
        List<Object[]> deployed = q.db().query("""
                SELECT a.id, a.mod_version FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND sa.loaded=1 AND a.mod_id=?""", rs -> new Object[]{rs.getLong(1), rs.getString(2)}, s.snapshotId(), p.modId());
        Set<Long> replaced = new LinkedHashSet<>();
        for (Object[] d : deployed) replaced.add((long) d[0]);

        String build = "project build " + p.modId() + " " + p.version() + " (" + p.jar().getFileName()
                + (p.jarStale() ? ", built before the latest source changes" : "") + ")";
        String where = s.historical() ? s.env() + "@" + s.at() : s.env();
        String note = replaced.contains(id) ? build + " = the jar deployed on " + where + "; answers are unchanged"
                : replaced.isEmpty() ? "with " + build + ", not deployed on " + where
                : "with " + build + " in place of " + where + "'s " + p.modId() + " " + deployed.getFirst()[1];
        replaced.remove(id);
        return s.withOverlay(new Scope.Overlay(p.modId(), added, replaced, note));
    }

    private static Long indexed(Db db, String sha) throws SQLException {
        return db.queryLong("SELECT id FROM artifact WHERE sha256=? AND index_version=?", sha, Db.INDEX_VERSION);
    }

    /** Runs {@code sevli project index} and waits for it; returns the last line of its output. */
    private static String index(QueryService q, String env, Path dir) throws IOException {
        Path log = Files.createTempFile("sevli-project-index", ".log"); // a file, not a pipe: a full pipe would stall the child
        try {
            Process proc = new ProcessBuilder(AutoSync.command(q.config(), "project", "index", dir.toString(), "--env", env))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            proc.getOutputStream().close();
            try {
                if (!proc.waitFor(INDEX_TIMEOUT_S, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    return "indexing took longer than " + INDEX_TIMEOUT_S + " s";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                return "interrupted";
            }
            String out = Files.readString(log, StandardCharsets.UTF_8).strip();
            return out.isEmpty() ? "no output" : out.substring(out.lastIndexOf('\n') + 1);
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
