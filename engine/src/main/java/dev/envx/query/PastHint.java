package dev.envx.query;

import dev.envx.store.Packs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * When a query finds nothing on the current server, says whether a past version had it:
 * {@code Not in the current server, but in past versions: origins-plus-plus 2.4 (4.0.4, 4.0.5). Ask with env=myserver@4.0.5.}
 *
 * <p>Only artifacts that past labeled snapshots loaded and the current one does not are checked (a few dozen jars),
 * so the hint is cheap and precise. Nothing is said when history has nothing either. History is never mixed into
 * answers; this only points to it (benchmark Q12: agents saw empty results and went to GitHub instead).
 */
final class PastHint {
    private final QueryService q;

    PastHint(QueryService q) {
        this.q = q;
    }

    /** artifact id -> labels of past snapshots that loaded it (oldest first); empty for historical scopes. */
    private Map<Long, Set<String>> pastOnly(Scope s) throws SQLException {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        if (s.historical()) return out;
        for (Object[] r : q.db().query("""
                SELECT sa.artifact_id, sn.label FROM snapshot_artifact sa JOIN snapshot sn ON sn.id=sa.snapshot_id
                WHERE sn.env=? AND sn.label IS NOT NULL AND sn.id<>? AND sa.loaded=1
                  AND NOT EXISTS (SELECT 1 FROM snapshot_artifact c WHERE c.snapshot_id=? AND c.artifact_id=sa.artifact_id AND c.loaded=1)
                ORDER BY sn.id""", rs -> new Object[]{rs.getLong(1), rs.getString(2)}, s.env(), s.snapshotId(), s.snapshotId())) {
            out.computeIfAbsent((Long) r[0], k -> new LinkedHashSet<>()).add((String) r[1]);
        }
        return out;
    }

    /** Mods whose id, name or file contains {@code term}. */
    String mods(Scope s, String term) {
        try {
            Map<Long, Set<String>> past = pastOnly(s);
            if (past.isEmpty() || term == null || term.isBlank()) return "";
            String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
            Map<Long, Set<String>> hits = new LinkedHashMap<>();
            for (var e : past.entrySet()) {
                // a mod still on the server under another version is not "past"
                int m = q.db().queryInt("SELECT count(*) FROM artifact a WHERE a.id=? AND a.mod_id IS NOT NULL AND "
                        + "(lower(a.mod_id) LIKE ? OR lower(coalesce(a.mod_name,'')) LIKE ? OR lower(a.file_name) LIKE ?) AND NOT EXISTS ("
                        + "SELECT 1 FROM snapshot_artifact c JOIN artifact b ON b.id=c.artifact_id WHERE c.snapshot_id=? AND c.loaded=1 AND b.mod_id=a.mod_id)",
                        e.getKey(), like, like, like, s.snapshotId());
                if (m > 0) hits.put(e.getKey(), e.getValue());
            }
            return render(s, hits);
        } catch (SQLException e) {
            return "";
        }
    }

    /** Classes by simple name, full name, or a {@code *} pattern on the simple name. */
    String classes(Scope s, String cls) {
        try {
            Map<Long, Set<String>> past = pastOnly(s);
            if (past.isEmpty() || cls == null || cls.isBlank()) return "";
            String c = cls.trim();
            String internal = c.replace('.', '/');
            String simple = internal.substring(internal.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            String ids = String.join(",", past.keySet().stream().map(String::valueOf).toList());
            List<Long> found = q.db().query("SELECT DISTINCT artifact_id FROM class WHERE artifact_id IN (" + ids + ") AND "
                            + (simple.contains("*") ? "simple LIKE ?" : "(simple = ? OR name = ? OR named = ?)") + " LIMIT 20",
                    rs -> rs.getLong(1), simple.contains("*") ? new Object[]{simple.replace('*', '%')} : new Object[]{simple, internal, internal});
            Map<Long, Set<String>> hits = new LinkedHashMap<>();
            for (long id : found) hits.put(id, past.get(id));
            return render(s, hits);
        } catch (SQLException e) {
            return "";
        }
    }

    /** Data files of past-only jars that match (grep, resources scope). Stops after a few files. */
    /** How long a no-match grep may spend looking through past versions' files. */
    static final long PAST_BUDGET_MS = 5_000;

    String resources(Scope s, Pattern pattern, String pathFilter) {
        try {
            Map<Long, Set<String>> past = pastOnly(s);
            if (past.isEmpty()) return "";
            // Past-only jars are rarely read, so their files come off a cold disk (a no-match grep took 55-71 s before
            // 1.2.1): read them in parallel and stop after PAST_BUDGET_MS, saying so.
            Map<Long, String> found = new java.util.concurrent.ConcurrentHashMap<>();
            long deadline = System.currentTimeMillis() + PAST_BUDGET_MS;
            java.util.concurrent.atomic.AtomicBoolean cut = new java.util.concurrent.atomic.AtomicBoolean();
            Map<Long, String> shas = new LinkedHashMap<>();
            for (long id : past.keySet()) shas.put(id, q.db().queryString("SELECT sha256 FROM artifact WHERE id=?", id));
            shas.entrySet().parallelStream().forEach(e -> {
                if (e.getValue() == null || found.size() >= 5) return;
                if (System.currentTimeMillis() > deadline) {
                    cut.set(true);
                    return;
                }
                Path root = q.config().home().resolve("resources").resolve(e.getValue());
                if (!Files.isDirectory(root)) return;
                if (Files.exists(Packs.of(root))) { // one file instead of thousands (ADR 0011)
                    try {
                        for (Object[] entry : Packs.read(Packs.of(root))) {
                            String rel = (String) entry[0];
                            if (pathFilter != null && !rel.toLowerCase(Locale.ROOT).contains(pathFilter)) continue;
                            @SuppressWarnings("unchecked")
                            List<String> lines = (List<String>) entry[1];
                            if (pattern.matcher(rel).find() || lines.stream().anyMatch(l -> pattern.matcher(l).find())) {
                                found.put(e.getKey(), rel);
                                return;
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // unreadable pack: skip this jar
                    }
                    return;
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        if (System.currentTimeMillis() > deadline) {
                            cut.set(true);
                            return;
                        }
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        if (pathFilter != null && !rel.toLowerCase(Locale.ROOT).contains(pathFilter)) continue;
                        if (pattern.matcher(rel).find() || (Files.size(f) <= 1024 * 1024
                                && pattern.matcher(Files.readString(f, StandardCharsets.UTF_8)).find())) {
                            found.put(e.getKey(), rel);
                            return;
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unreadable file or tree: skip this jar
                }
            });
            Map<Long, Set<String>> hits = new LinkedHashMap<>();
            List<String> examples = new ArrayList<>();
            for (var e : past.entrySet()) { // keep the history order
                if (!found.containsKey(e.getKey()) || hits.size() >= 5) continue;
                hits.put(e.getKey(), e.getValue());
                if (examples.size() < 2) examples.add(found.get(e.getKey()));
            }
            if (cut.get() && hits.isEmpty()) return "\n(past versions checked partly: time limit; ask with env=<name>@<label> to search one)";
            String r = render(s, hits);
            return r.isEmpty() || examples.isEmpty() ? r : r.replaceFirst("\\. Ask", ", e.g. " + String.join(", ", examples) + ". Ask");
        } catch (SQLException e) {
            return "";
        }
    }

    private String render(Scope s, Map<Long, Set<String>> hits) throws SQLException {
        if (hits.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        String newest = null;
        for (var e : hits.entrySet()) {
            if (parts.size() == 3) {
                parts.add("+" + (hits.size() - 3) + " more");
                break;
            }
            parts.add(q.label(e.getKey()) + " (" + String.join(", ", e.getValue()) + ")");
            if (newest == null) for (String l : e.getValue()) newest = l; // the first hit's latest version (labels come oldest first)
        }
        return "\nNot in the current server, but in past versions: " + String.join("; ", parts)
                + ". Ask with env=" + s.env() + "@" + newest + ".";
    }
}
