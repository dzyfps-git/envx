package dev.sevli.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.catalog.Catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Optional storage cleanup (ADR 0014): never automatic, always previewed, and only Sevli's own data inside the data
 * home. Nothing a kept snapshot, an installed baseline or a project build uses is ever offered. Each item says what
 * happens if it is needed again, checked by the exact file hash:
 * <ul>
 *   <li>{@code rebuildable}: an official source still serves that exact file (Modrinth, by hash);</li>
 *   <li>{@code not_verified}: the check could not run (offline, network error): evidence is missing, not the file;</li>
 *   <li>{@code unrecoverable}: no known source has it, or it only ever existed in the index (past server states).</li>
 * </ul>
 * Files go to the Recycle Bin where the system offers one.
 */
public final class Cleanup {
    private final Config config;
    private final Db db;
    private final Path home;

    public Cleanup(Config config, Db db) {
        this.config = config;
        this.db = db;
        this.home = config.home();
    }

    /** A jar freed with an item, and whether it can be fetched again. */
    public record Jar(long artifactId, String sha256, String name, long bytes, String recovery, String note) {}

    /** One thing the user may free. {@code recovery}: the worst of its parts. */
    public record Item(String id, String kind, String label, long bytes, String why, String recovery, String note,
                       boolean historyLoss, List<Long> snapshots, List<Jar> jars, List<String> files) {}

    // ---------------------------------------------------------------- preview

    /**
     * What may be freed: history of servers no longer connected, snapshots the user names ({@code env@label} or
     * {@code env#id}, never a server's current one), the jar data only they use, and leftover temporary files.
     * {@code checkSources}: ask Modrinth whether each jar can be downloaded again (by its hash; nothing else is sent).
     */
    public List<Item> preview(List<String> snapshotsAsked, boolean checkSources) throws SQLException, IOException {
        List<Item> items = new ArrayList<>();
        Map<String, List<Long>> gone = new LinkedHashMap<>();
        for (Object[] r : db.query("SELECT env, id FROM snapshot ORDER BY env, id", rs -> new Object[]{rs.getString(1), rs.getLong(2)})) {
            if (!config.environments.containsKey((String) r[0])) gone.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add((Long) r[1]);
        }
        for (var e : gone.entrySet()) {
            items.add(history("history:" + e.getKey(), "history of " + e.getKey() + ", a server no longer connected (" + e.getValue().size() + " snapshots)",
                    "the server was removed from Sevli; its past versions stay until you free them", e.getValue()));
        }
        for (String asked : snapshotsAsked) {
            long id = resolve(asked);
            items.add(history("snapshot:" + asked, "snapshot " + asked, "you asked to free it", List.of(id)));
        }
        List<Path> tmp = leftovers();
        if (!tmp.isEmpty()) {
            long bytes = 0;
            for (Path p : tmp) bytes += size(p);
            items.add(new Item("leftovers", "leftovers", tmp.size() + " unfinished temporary files", bytes,
                    "left by an interrupted sync or decompile", "rebuildable", "nothing is lost: they are incomplete copies",
                    false, List.of(), List.of(), tmp.stream().map(Path::toString).toList()));
        }
        if (checkSources) items = checkRecovery(items);
        return items;
    }

    private long resolve(String asked) throws SQLException {
        int at = asked.indexOf('@'), hash = asked.indexOf('#');
        String env = asked.substring(0, at >= 0 ? at : hash >= 0 ? hash : asked.length());
        Long id = at >= 0 ? db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND label=?", env, asked.substring(at + 1))
                : hash >= 0 ? db.queryLong("SELECT id FROM snapshot WHERE env=? AND id=?", env, Long.parseLong(asked.substring(hash + 1))) : null;
        if (id == null) throw new IllegalArgumentException("no snapshot " + asked + " (sevli history <name> lists them; name one as <name>@<label> or <name>#<id>)");
        Long current = db.queryLong("SELECT id FROM snapshot WHERE env=? ORDER BY kind='sync' DESC, id DESC LIMIT 1", env);
        if (id.equals(current) && config.environments.containsKey(env)) {
            throw new IllegalArgumentException(asked + " is what " + env + " runs now; its current snapshot is never freed");
        }
        return id;
    }

    /** Snapshots to free, with the jars only they use (nothing a kept snapshot, a baseline or a project build uses). */
    private Item history(String id, String label, String why, List<Long> snapshots) throws SQLException, IOException {
        String in = ids(snapshots);
        List<Jar> jars = new ArrayList<>();
        long bytes = 0;
        for (Object[] r : db.query("""
                SELECT a.id, a.sha256, coalesce(a.mod_id || ' ' || coalesce(a.mod_version, ''), a.file_name) FROM artifact a
                WHERE a.kind <> 'minecraft'
                  AND a.id IN (SELECT artifact_id FROM snapshot_artifact WHERE snapshot_id IN (%s))
                  AND a.id NOT IN (SELECT artifact_id FROM snapshot_artifact WHERE snapshot_id NOT IN (%s))
                ORDER BY 3""".formatted(in, in), rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3).trim()})) {
            long b = jarBytes((String) r[1]);
            bytes += b;
            jars.add(new Jar((long) r[0], (String) r[1], (String) r[2], b, "not_verified", "not checked yet"));
        }
        for (long s : snapshots) bytes += size(home.resolve("texts").resolve("snapshot-" + s + ".pack"));
        return new Item(id, "history", label, bytes, why, "unrecoverable",
                "past server states exist only in the index: their mod lists, configs and diffs cannot be rebuilt", true,
                List.copyOf(snapshots), jars, List.of());
    }

    /** Files of one jar across the caches: the copy, its resources, its remapped jar and decompiled source. */
    private List<Path> jarFiles(String sha) throws IOException {
        List<Path> out = new ArrayList<>();
        out.add(home.resolve("artifacts").resolve(sha.substring(0, 2)).resolve(sha + ".jar"));
        out.add(home.resolve("resources").resolve(sha));
        for (String cache : List.of("remapped", "decomp")) { // per-jar caches are named <sha prefix>-...
            Path dir = home.resolve(cache);
            if (!Files.isDirectory(dir)) continue;
            try (var list = Files.list(dir)) {
                list.filter(f -> f.getFileName().toString().startsWith(sha.substring(0, 16) + "-")).forEach(out::add);
            }
        }
        out.removeIf(p -> !Files.exists(p));
        return out;
    }

    private long jarBytes(String sha) throws IOException {
        long b = 0;
        for (Path p : jarFiles(sha)) b += size(p);
        return b;
    }

    /** Temporary files an interrupted process left behind ({@code *.tmp} in the content-addressed caches). */
    private List<Path> leftovers() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String cache : List.of("artifacts", "remapped", "texts")) {
            Path dir = home.resolve(cache);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir, 2)) {
                walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".tmp")).forEach(out::add);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- recovery check

    private List<Item> checkRecovery(List<Item> items) {
        Map<String, Path> local = new LinkedHashMap<>();
        for (Item i : items) for (Jar j : i.jars()) local.put(j.sha256(), home.resolve("artifacts").resolve(j.sha256().substring(0, 2)).resolve(j.sha256() + ".jar"));
        Map<String, String> sha512 = new LinkedHashMap<>(); // sha256 -> sha512 of the local copy
        for (var e : local.entrySet()) {
            if (Files.isRegularFile(e.getValue())) {
                try {
                    sha512.put(e.getKey(), digest(e.getValue(), "SHA-512"));
                } catch (IOException ignored) {
                    // unreadable: stays not verified
                }
            }
        }
        Map<String, String> found = null; // sha512 -> file url; null when the check could not run
        String failure = null;
        if (!sha512.isEmpty()) {
            try {
                found = modrinth(new ArrayList<>(sha512.values()));
            } catch (IOException e) {
                failure = e.getMessage();
            }
        }
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.jars().isEmpty()) {
                out.add(i);
                continue;
            }
            List<Jar> jars = new ArrayList<>();
            for (Jar j : i.jars()) {
                String h = sha512.get(j.sha256());
                if (h == null) jars.add(new Jar(j.artifactId(), j.sha256(), j.name(), j.bytes(), "unrecoverable", "no copy of the jar is left to identify it by"));
                else if (found == null) jars.add(new Jar(j.artifactId(), j.sha256(), j.name(), j.bytes(), "not_verified", "the check could not run (" + failure + ")"));
                else if (found.containsKey(h)) jars.add(new Jar(j.artifactId(), j.sha256(), j.name(), j.bytes(), "rebuildable", "Modrinth serves this exact file"));
                else jars.add(new Jar(j.artifactId(), j.sha256(), j.name(), j.bytes(), "unrecoverable", "no known source has this exact file (checked on Modrinth)"));
            }
            out.add(new Item(i.id(), i.kind(), i.label(), i.bytes(), i.why(), i.recovery(), i.note(), i.historyLoss(), i.snapshots(), jars, i.files()));
        }
        return out;
    }

    /** Asks Modrinth which of these sha512 hashes it serves. Only hashes are sent. */
    static Map<String, String> modrinth(List<String> hashes) throws IOException {
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        hashes.forEach(a::add);
        body.add("hashes", a);
        body.addProperty("algorithm", "sha512");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/version_files")).timeout(Duration.ofSeconds(30))
                .header("User-Agent", Catalog.USER_AGENT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                    .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("Modrinth answered HTTP " + r.statusCode());
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : JsonParser.parseString(r.body()).getAsJsonObject().entrySet()) {
                for (var f : e.getValue().getAsJsonObject().getAsJsonArray("files")) {
                    JsonObject file = f.getAsJsonObject();
                    String h = file.getAsJsonObject("hashes").get("sha512").getAsString();
                    if (h.equalsIgnoreCase(e.getKey())) out.put(e.getKey(), file.get("url").getAsString());
                }
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (RuntimeException e) {
            throw new IOException("unexpected answer from Modrinth (" + e.getMessage() + ")", e);
        }
    }

    static String digest(Path file, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------- freeing

    /** Frees exactly these items (as the preview described them). Returns the bytes freed. */
    public long free(List<Item> items, Consumer<String> progress) throws SQLException, IOException {
        long freed = 0;
        for (Item i : items) {
            if (!i.snapshots().isEmpty()) {
                String in = ids(i.snapshots());
                db.inTransaction(() -> {
                    for (String t : List.of("snapshot_file", "snapshot_artifact", "snapshot_text", "snapshot_loaded", "snapshot_unindexed")) {
                        if (db.queryInt("SELECT count(*) FROM sqlite_master WHERE name=?", t) > 0) db.update("DELETE FROM " + t + " WHERE snapshot_id IN (" + in + ")");
                    }
                    db.update("DELETE FROM snapshot WHERE id IN (" + in + ")");
                });
                for (long s : i.snapshots()) freed += trash(home.resolve("texts").resolve("snapshot-" + s + ".pack"));
            }
            String freeing = ids(i.jars().stream().map(Jar::artifactId).toList());
            for (Jar j : i.jars()) {
                // still unused? (a sync may have run since the preview) and not bundled in a jar that stays (a project build)
                if (db.queryInt("SELECT count(*) FROM snapshot_artifact WHERE artifact_id=?", j.artifactId()) > 0) continue;
                if (db.queryInt("SELECT count(*) FROM artifact_nested WHERE child_id=? AND parent_id NOT IN (" + freeing + ")", j.artifactId()) > 0) continue;
                List<Path> files = jarFiles(j.sha256());
                db.inTransaction(() -> {
                    db.update("DELETE FROM artifact_nested WHERE parent_id=? OR child_id=?", j.artifactId(), j.artifactId());
                    db.update("DELETE FROM artifact WHERE id=?", j.artifactId()); // classes, members, refs, mixins, resources cascade
                });
                for (Path f : files) freed += trash(f);
            }
            for (String f : i.files()) freed += trash(Path.of(f));
            progress.accept("freed " + i.label());
        }
        return freed;
    }

    /** Sevli's own copies for a removed server ({@code envs/<env>/}: current configs, logs, diffs). */
    public long trashEnvFolder(String env) throws IOException {
        if (env.isBlank() || env.contains("/") || env.contains("\\") || env.contains("..")) throw new IOException("not a server name: " + env);
        return trash(home.resolve("envs").resolve(env));
    }

    /** Moves a file or folder inside the data home to the Recycle Bin (or deletes it where there is none). */
    long trash(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(home.toAbsolutePath().normalize())) throw new IOException("refusing to touch " + abs + ": outside Sevli's data home");
        if (!Files.exists(abs)) return 0;
        long bytes = size(abs);
        if (!Trash.move(abs)) {
            try (var walk = Files.walk(abs)) {
                for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
            }
        }
        return bytes;
    }

    static long size(Path p) throws IOException {
        if (!Files.exists(p)) return 0;
        if (Files.isRegularFile(p)) return Files.size(p);
        long[] total = {0};
        try (var walk = Files.walk(p)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    total[0] += Files.size(f);
                } catch (IOException ignored) {
                    // vanished
                }
            });
        }
        return total[0];
    }

    private static String ids(List<Long> ids) {
        Set<String> s = new LinkedHashSet<>();
        ids.forEach(i -> s.add(String.valueOf(i)));
        return String.join(",", s);
    }

    /** The Recycle Bin through the desktop, when there is one (not on a headless system or in tests). */
    static final class Trash {
        static volatile boolean disabled = Boolean.getBoolean("sevli.noTrash");

        static boolean move(Path p) {
            if (disabled) return false;
            try {
                if (!java.awt.Desktop.isDesktopSupported()) return false;
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                return d.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && d.moveToTrash(p.toFile());
            } catch (RuntimeException | Error e) {
                return false;
            }
        }
    }
}
