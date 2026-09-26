package dev.sevli.env;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.sevli.Config;
import dev.sevli.fabric.FabricBase;
import dev.sevli.index.Indexer;
import dev.sevli.index.JarParser;
import dev.sevli.store.Db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Syncs environments from their (read-only) source into snapshots and computes pack diffs. */
public final class Environments {
    /** Server folders whose text files are mirrored for {@code grep}; everything else is ignored. */
    private static final List<String> TEXT_DIRS = List.of("config", "defaultconfigs", "datapacks", "kubejs",
            "world/datapacks", "global_packs", "moonlight-global-datapacks");
    private static final Set<String> TEXT_EXT = Set.of("json", "json5", "toml", "properties", "cfg", "conf", "txt", "yml",
            "yaml", "js", "mcfunction", "snbt", "zs", "ini", "xml", "csv", "mcmeta", "md");
    private static final long MAX_TEXT_BYTES = 1024 * 1024;

    private final Config config;
    private final Db db;
    private final Path home;

    public Environments(Config config, Db db) {
        this.config = config;
        this.db = db;
        this.home = config.home();
    }

    public record SyncResult(long snapshotId, String summary, String diff) {}

    /** A jar on the server that was hashed but not indexed (ADR 0014). */
    public record Unindexed(String relPath, String sha256, long size, String modId, String version, String reason) {
        public String label() {
            String name = modId != null ? modId + (version != null ? " " + version : "") : relPath.substring(relPath.lastIndexOf('/') + 1);
            return reason.equals("revoked") ? name + " (withdrawn)" : name;
        }
    }

    /**
     * Which jars a sync of {@code env} may index: the supported list the user accepted for it, plus jars it already
     * indexed before (a retired version keeps working). Null when everything is indexed (the maintainer's install).
     */
    private record Gate(dev.sevli.catalog.Supported list, int accepted, Set<String> usedBefore) {
        boolean allows(String sha) {
            return list.acceptable(sha, accepted, usedBefore.contains(sha));
        }

        String reason(String sha) {
            var j = list.get(sha);
            if (j != null && j.revoked()) return "revoked";
            return list.newlySupported(sha, accepted) ? "newly_supported" : "not_supported";
        }
    }

    private Gate gate(String env) throws SQLException, IOException {
        if (config.indexesEverything()) return null;
        Config.EnvDef def = def(env);
        dev.sevli.catalog.Supported list = dev.sevli.catalog.Supported.current(config, false);
        if (def.supportAccepted == null) { // connecting a server accepts the supported list of that moment
            def.supportAccepted = list.catalogVersion;
            config.save();
        }
        Set<String> used = new HashSet<>(db.query("""
                SELECT DISTINCT f.sha256 FROM snapshot_file f JOIN snapshot s ON s.id=f.snapshot_id WHERE s.env=?""",
                rs -> rs.getString(1), env));
        return new Gate(list, def.supportAccepted, used);
    }

    /** A config/datapack text file as stored: redacted, then kept by hash under {@code texts/}. */
    private record TextRef(String sha256, long size) {}

    private Indexer indexer;

    /**
     * Pulls the environment's live source (read-only) into a new snapshot. When nothing changed since the
     * last sync, no snapshot is added; the last one is marked as checked. That keeps frequent syncs cheap
     * and the history to one snapshot per real change.
     */
    public SyncResult sync(String env, Consumer<String> progress) throws IOException, SQLException {
        Config.EnvDef def = def(env);
        if (def.sources.isEmpty()) throw new IllegalArgumentException("Environment " + env + " has no sources");
        for (String src : def.sources) {
            if (!src.startsWith("ssh://") && config.isDenied(Path.of(src))) {
                throw new IllegalArgumentException("Source is inside a denied root: " + src);
            }
        }
        // Nothing below is committed until the snapshot transaction, so a source dropping out
        // mid-sync (power cut, share disconnect) leaves the previous snapshot current.
        EnvironmentSource source = EnvironmentSource.firstReachable(def.sources, progress);
        dev.sevli.catalog.ServerCheck.require(source, def); // only servers on a supported baseline (ADR 0013)
        long mcId = ensureBase(def, progress);
        return capture(env, mcId, source, "sync", null, progress);
    }

    /**
     * Adds a past version of the environment from a server folder (a full backup, an unpacked server pack).
     * Reads only what a sync reads: mods/ jars (stored once by hash, so jars already known cost nothing),
     * config/datapack text, server.properties (redacted) and logs/latest.log for the loaded list. Worlds,
     * libraries, backups and other logs are never read. The current snapshot is unaffected.
     */
    public SyncResult importFolder(String env, Path folder, String label, Consumer<String> progress) throws IOException, SQLException {
        Config.EnvDef def = def(env);
        Path dir = folder.toAbsolutePath().normalize();
        if (config.isDenied(dir)) throw new IllegalArgumentException("Folder is inside a denied root: " + dir);
        if (!Files.isDirectory(dir.resolve("mods"))) throw new IllegalArgumentException("No mods/ folder in " + dir + "; pass a server folder");
        FolderSource source = new FolderSource(dir);
        dev.sevli.catalog.ServerCheck.require(source, def);
        long mcId = ensureBase(def, progress);
        return capture(env, mcId, source, "import", label, progress);
    }

    private Config.EnvDef def(String env) {
        Config.EnvDef def = config.environments.get(env);
        if (def == null) throw new IllegalArgumentException("Unknown environment: " + env);
        return def;
    }

    private long ensureBase(Config.EnvDef def, Consumer<String> progress) throws IOException, SQLException {
        FabricBase base = FabricBase.forEnv(home, def.minecraft, def.mappings);
        if (!base.isProvisioned()) throw new IllegalStateException(dev.sevli.catalog.Baselines.missing(def)); // never downloaded unasked
        progress.accept("loading mappings " + base.id());
        indexer = new Indexer(db, home, base.loadMappings());
        String mcSha = hashLocal(base.intermediaryJar());
        long mcId = indexer.ensureAll(List.of(new Indexer.Job(base.intermediaryJar(), mcSha, "minecraft")), progress)
                .values().iterator().next().artifactId();
        db.setMeta("base." + base.id() + ".artifact", String.valueOf(mcId));
        return mcId;
    }

    private SyncResult capture(String env, long mcId, EnvironmentSource source, String kind, String labelArg, Consumer<String> progress)
            throws IOException, SQLException {
        progress.accept("listing mods at " + source.describe());
        List<EnvironmentSource.Entry> mods = source.listMods();
        Gate gate = gate(env);
        List<Indexer.Job> jobs = new ArrayList<>();
        Map<Path, EnvironmentSource.Entry> byLocal = new HashMap<>();
        List<EnvironmentSource.Entry> fetch = new ArrayList<>();
        List<Unindexed> unindexed = new ArrayList<>();
        List<EnvironmentSource.Entry> unindexedUnread = new ArrayList<>(); // hash known, id and version not yet
        for (EnvironmentSource.Entry e : mods) {
            String sha = e.sha256() != null ? e.sha256() : cachedHash(source.describe() + "|" + e.relPath(), e);
            if (sha != null && gate != null && !gate.allows(sha)) { // hashed only: neither stored nor indexed
                String[] known = knownMod(sha);
                if (known != null) unindexed.add(new Unindexed(e.relPath(), sha, e.size(), known[0], known[1], gate.reason(sha)));
                else unindexedUnread.add(withSha(e, sha));
            } else if (sha != null && Files.exists(indexer.artifactPath(sha))) {
                Path local = indexer.artifactPath(sha);
                jobs.add(new Indexer.Job(local, sha, "mod"));
                byLocal.put(local, withSha(e, sha));
            } else {
                fetch.add(e);
            }
        }
        progress.accept(mods.size() + " jars, " + fetch.size() + " new or changed to fetch");
        fetch.addAll(unindexedUnread); // read in memory for their id and version only
        for (int i = 0; i < fetch.size(); i += 16) {
            List<EnvironmentSource.Entry> batch = fetch.subList(i, Math.min(fetch.size(), i + 16));
            Map<String, byte[]> data = source.readMany(batch.stream().map(EnvironmentSource.Entry::relPath).toList());
            for (EnvironmentSource.Entry e : batch) {
                byte[] bytes = data.get(e.relPath());
                if (bytes == null) {
                    progress.accept("WARN could not read " + e.relPath());
                    continue;
                }
                String sha = JarParser.sha256(bytes);
                if (gate != null && !gate.allows(sha)) {
                    rememberHash(source.describe() + "|" + e.relPath(), e, sha);
                    String[] idv = JarParser.modIdVersion(bytes);
                    unindexed.add(new Unindexed(e.relPath(), sha, e.size(), idv[0], idv[1], gate.reason(sha)));
                    continue;
                }
                Path local = indexer.artifactPath(sha);
                writeAtomically(local, bytes); // a killed background sync must never leave a truncated jar under its hash
                rememberHash(source.describe() + "|" + e.relPath(), e, sha);
                jobs.add(new Indexer.Job(local, sha, "mod"));
                byLocal.put(local, withSha(e, sha));
            }
            progress.accept("fetched " + Math.min(fetch.size(), i + 16) + "/" + fetch.size());
        }

        Map<Path, Indexer.Result> results = indexer.ensureAll(jobs, progress);
        for (var r : results.entrySet()) { // jars are parsed from the content-addressed copy; keep the real name
            String rel = byLocal.get(r.getKey()).relPath();
            db.update("UPDATE artifact SET file_name=? WHERE id=?", rel.substring(rel.lastIndexOf('/') + 1), r.getValue().artifactId());
        }
        List<String> warnings = new ArrayList<>();
        results.forEach((p, r) -> r.warnings().forEach(w -> warnings.add(byLocal.get(p).relPath() + ": " + w)));

        LoaderSource loaderLog = loaderLog(source);
        List<LoaderLog.LoadedMod> loaded = loaderLog.mods();
        long logMtime = loaderLog.mtime();
        Map<Long, Long> jarMtime = new HashMap<>();
        results.forEach((p, r) -> jarMtime.put(r.artifactId(), byLocal.get(p).mtime()));

        progress.accept("storing config/datapack text files");
        Map<String, TextRef> texts = storeTexts(source);
        PackInfo pack = PackInfo.fromBcc(source.readIfExists(PackInfo.BCC));

        // Same jars, same text, same loaded list = same snapshot. Identity is by content, not by time.
        List<String> fp = new ArrayList<>();
        results.forEach((p, r) -> fp.add("jar " + byLocal.get(p).relPath() + " " + r.sha256()));
        unindexed.forEach(u -> fp.add("unindexed " + u.relPath() + " " + u.sha256() + " " + u.reason()));
        texts.forEach((rel, t) -> fp.add("text " + rel + " " + t.sha256()));
        loaded.forEach(m -> fp.add("loaded " + m.id() + " " + m.version() + (m.nested() ? " nested" : "")));
        Collections.sort(fp);
        String fingerprint = JarParser.sha256(String.join("\n", fp).getBytes(StandardCharsets.UTF_8));

        String label = labelArg != null ? labelArg : pack != null ? pack.version() : null;
        String now = Instant.now().toString();
        if (kind.equals("sync")) {
            Object[] last = lastSync(env);
            if (last != null && fingerprint.equals(last[1])) {
                db.update("UPDATE snapshot SET checked_at=? WHERE id=?", now, last[0]);
                packTexts((long) last[0]); // snapshots from before 1.4.3 have none
                return new SyncResult((long) last[0], "no changes since snapshot " + last[0] + " of " + env
                        + (label != null ? " (" + label + ")" : "") + "; marked as checked\n", "");
            }
        } else {
            if (label == null) throw new IllegalArgumentException("No pack version found (" + PackInfo.BCC + "); pass --label <name>");
            Long same = db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND fingerprint=?", env, fingerprint);
            if (same != null) return new SyncResult(same, "already in history as snapshot " + same + "; nothing added\n", "");
            if (db.queryInt("SELECT count(*) FROM snapshot WHERE env=? AND label=?", env, label) > 0) {
                throw new IllegalArgumentException("Label '" + label + "' is already used by a different snapshot of " + env
                        + "; pass --label <name> (e.g. " + label + "-backup)");
            }
        }

        long[] snap = new long[1];
        List<String> stale = new ArrayList<>();
        db.inTransaction(() -> {
            db.update("""
                    INSERT INTO snapshot(env, taken_at, source, loader_list, kind, label, pack, pack_name, checked_at, fingerprint)
                    VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    env, now, source.describe(), loaded.isEmpty() ? 0 : 1, kind, label,
                    pack == null ? null : pack.pack(), pack == null ? null : pack.name(), now, fingerprint);
            snap[0] = db.queryLong("SELECT max(id) FROM snapshot WHERE env=?", env);
            for (var r : results.entrySet()) {
                EnvironmentSource.Entry e = byLocal.get(r.getKey());
                db.update("INSERT OR REPLACE INTO snapshot_file(snapshot_id, rel_path, sha256, size) VALUES(?,?,?,?)",
                        snap[0], e.relPath(), r.getValue().sha256(), e.size());
            }
            for (Unindexed u : unindexed) {
                db.update("INSERT OR REPLACE INTO snapshot_unindexed(snapshot_id, rel_path, sha256, size, mod_id, version, reason) VALUES(?,?,?,?,?,?,?)",
                        snap[0], u.relPath(), u.sha256(), u.size(), u.modId(), u.version(), u.reason());
            }
            for (var t : texts.entrySet()) {
                db.update("INSERT OR REPLACE INTO snapshot_text(snapshot_id, rel_path, sha256, size) VALUES(?,?,?,?)",
                        snap[0], t.getKey(), t.getValue().sha256(), t.getValue().size());
            }
            for (LoaderLog.LoadedMod m : loaded) {
                db.update("INSERT OR IGNORE INTO snapshot_loaded(snapshot_id, mod_id, version, nested) VALUES(?,?,?,?)",
                        snap[0], m.id(), m.version(), m.nested() ? 1 : 0);
            }
            Config.EnvDef def = config.environments.get(env);
            stale.addAll(writeArtifactClosure(snap[0], mcId, results.values().stream().map(Indexer.Result::artifactId).toList(),
                    loaded, jarMtime, logMtime, def == null || !"client".equals(def.side)));
        });

        String diff = "";
        StringBuilder summary = new StringBuilder();
        int loadedCount = db.queryInt("SELECT count(*) FROM snapshot_artifact WHERE snapshot_id=? AND loaded=1", snap[0]);
        summary.append(kind.equals("import") ? "imported" : "snapshot").append(' ').append(snap[0]).append(" of ").append(env)
                .append(label != null ? " (" + label + ")" : "").append(": ").append(mods.size()).append(" jars, ")
                .append(loadedCount).append(" loaded artifacts (incl. nested + minecraft), ").append(texts.size()).append(" text files\n");
        if (!unindexed.isEmpty()) summary.append(coverage(env, mods.size(), unindexed));
        packTexts(snap[0]);
        if (kind.equals("sync")) { // the current copies people browse; history lives in the DB and texts/
            refreshMirror(env, texts);
            writeLoaderList(env, loaded);
            Long prev = db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND kind='sync' AND id<?", env, snap[0]);
            diff = prev == null ? "# " + env + ": first snapshot (" + snap[0] + "), nothing to compare\n" : diff(prev, snap[0]);
            Path diffFile = home.resolve("envs").resolve(env).resolve("diffs").resolve(snap[0] + ".md");
            Files.createDirectories(diffFile.getParent());
            Files.writeString(diffFile, diff);
            String prevPack = prev == null ? null : db.queryString("SELECT pack FROM snapshot WHERE id=?", prev);
            if (prevPack != null && pack != null && !prevPack.equals(pack.pack())) {
                summary.append("pack changed: ").append(db.queryString("SELECT coalesce(pack_name, pack) FROM snapshot WHERE id=?", prev))
                        .append(" -> ").append(pack.name()).append(" (the earlier pack stays in history)\n");
            }
        }
        summary.append(loaded.isEmpty()
                ? "no loader mod list in logs/ (latest.log or rotated logs): all nested jars treated as loaded\n"
                : "loader list: " + loaded.size() + " mods from " + loaderLog.file() + "\n");
        if (!stale.isEmpty()) {
            summary.append("note: ").append(stale.size()).append(" jars added after the server's last restart (not in its loader log yet): ")
                    .append(String.join(", ", stale.subList(0, Math.min(8, stale.size())))).append(stale.size() > 8 ? ", ..." : "").append('\n');
        }
        if (!warnings.isEmpty()) summary.append(warnings.size()).append(" index warnings (see sevli warnings)\n");
        Files.createDirectories(home.resolve("envs").resolve(env));
        if (kind.equals("sync")) Files.write(home.resolve("envs").resolve(env).resolve("warnings.txt"), warnings);
        return new SyncResult(snap[0], summary.toString(), diff);
    }

    /** How much of a snapshot's server is indexed: its indexed jars, and the jars that were only hashed. */
    public record Coverage(int indexed, List<Unindexed> unindexed) {
        public int total() {
            return indexed + unindexed.size();
        }

        public long count(String reason) {
            return unindexed.stream().filter(u -> u.reason().equals(reason)).count();
        }
    }

    public static Coverage coverage(Db db, long snapshotId) throws SQLException {
        int indexed = db.queryInt("SELECT count(*) FROM snapshot_file WHERE snapshot_id=?", snapshotId);
        // read-only processes do not upgrade the index; before v3 nothing was left unindexed
        if (db.queryInt("SELECT count(*) FROM sqlite_master WHERE name='snapshot_unindexed'") == 0) return new Coverage(indexed, List.of());
        List<Unindexed> rows = db.query("SELECT rel_path, sha256, size, mod_id, version, reason FROM snapshot_unindexed WHERE snapshot_id=? ORDER BY rel_path",
                rs -> new Unindexed(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)), snapshotId);
        return new Coverage(indexed, rows);
    }

    /** "480 of 500 jars indexed" and what the rest are, for a sync's summary. */
    static String coverage(String env, int total, List<Unindexed> unindexed) {
        StringBuilder sb = new StringBuilder().append(total - unindexed.size()).append(" of ").append(total).append(" jars indexed");
        for (String reason : List.of("not_supported", "newly_supported", "revoked")) {
            List<String> names = unindexed.stream().filter(u -> u.reason().equals(reason)).map(Unindexed::label).sorted().toList();
            if (names.isEmpty()) continue;
            sb.append("; ").append(names.size()).append(switch (reason) {
                case "not_supported" -> " not supported yet";
                case "newly_supported" -> " newly supported (sevli accept " + env + " indexes them)";
                default -> " withdrawn from support";
            }).append(": ").append(String.join(", ", names.subList(0, Math.min(8, names.size())))).append(names.size() > 8 ? ", ..." : "");
        }
        return sb.append('\n').toString();
    }

    /** {id, version} of a jar known from an earlier snapshot (indexed or not), without reading it again; else null. */
    private String[] knownMod(String sha) throws SQLException {
        var rows = db.query("SELECT mod_id, version FROM snapshot_unindexed WHERE sha256=? LIMIT 1", rs -> new String[]{rs.getString(1), rs.getString(2)}, sha);
        if (!rows.isEmpty()) return rows.getFirst();
        rows = db.query("SELECT mod_id, mod_version FROM artifact WHERE sha256=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, sha);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record LoaderSource(List<LoaderLog.LoadedMod> mods, long mtime, String file) {}

    /**
     * The loader's resolved mod list is printed once, at server start. latest.log rolls over (daily, or on
     * restart), so when it no longer has the list, the newest rotated {@code logs/*.log.gz} that does is used.
     */
    private LoaderSource loaderLog(EnvironmentSource source) throws IOException {
        List<EnvironmentSource.Entry> logs = new ArrayList<>(source.listTree("logs", Long.MAX_VALUE / 2));
        logs.removeIf(e -> !e.relPath().equals("logs/latest.log") && !e.relPath().endsWith(".log.gz"));
        logs.sort((a, b) -> a.relPath().equals("logs/latest.log") ? -1 : b.relPath().equals("logs/latest.log") ? 1 : Long.compare(b.mtime(), a.mtime()));
        for (EnvironmentSource.Entry e : logs.subList(0, Math.min(logs.size(), 12))) {
            byte[] raw = source.readIfExists(e.relPath());
            if (raw == null) continue;
            if (e.relPath().endsWith(".gz")) {
                try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                    raw = in.readAllBytes();
                } catch (IOException bad) {
                    continue;
                }
            }
            List<LoaderLog.LoadedMod> mods = LoaderLog.parse(new String(raw, StandardCharsets.UTF_8));
            if (!mods.isEmpty()) return new LoaderSource(mods, e.mtime(), e.relPath());
        }
        return new LoaderSource(List.of(), 0, null);
    }

    /** {id, fingerprint} of the environment's latest live-source snapshot, or null. */
    private Object[] lastSync(String env) throws SQLException {
        var rows = db.query("SELECT id, fingerprint FROM snapshot WHERE env=? AND kind='sync' ORDER BY id DESC LIMIT 1",
                rs -> new Object[]{rs.getLong(1), rs.getString(2)}, env);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Records every artifact in the snapshot: Minecraft, the top-level jars and all nested jars.
     * With a loader list, a mod counts as loaded only if the loader loaded that exact id+version,
     * which is how a lower-version jar-in-jar copy is excluded.
     *
     * <p>A top-level jar missing from the list is either not loaded (client-only, or replaced by a
     * provider mod) when the log is newer than the jar, or new since the last restart when the jar is
     * newer; the latter are returned so the summary can say the log is stale for them.
     */
    private List<String> writeArtifactClosure(long snapshot, long mcId, List<Long> topLevel, List<LoaderLog.LoadedMod> loaded,
                                              Map<Long, Long> jarMtime, long logMtime, boolean server) throws SQLException {
        this.server = server;
        Set<String> loadedKeys = new HashSet<>();
        for (LoaderLog.LoadedMod m : loaded) loadedKeys.add(m.id() + " " + LoaderLog.normalizeVersion(m.version()));
        List<String> stale = new ArrayList<>();
        db.update("INSERT OR REPLACE INTO snapshot_artifact(snapshot_id, artifact_id, loaded) VALUES(?,?,1)", snapshot, mcId);
        Set<Long> seen = new HashSet<>();
        for (long id : topLevel) {
            boolean isLoaded = isLoaded(id, loadedKeys);
            if (!loadedKeys.isEmpty() && !isLoaded && jarMtime.getOrDefault(id, 0L) > logMtime) {
                stale.add(db.queryString("SELECT coalesce(mod_id || ' ' || mod_version, file_name) FROM artifact WHERE id=?", id));
                isLoaded = true; // added after the last restart: assume it will load
            }
            addWithChildren(snapshot, id, isLoaded, loadedKeys, seen);
        }
        return stale;
    }

    private void addWithChildren(long snapshot, long id, boolean loaded, Set<String> keys, Set<Long> seen) throws SQLException {
        Integer prev = seen.add(id) ? null : db.queryInt("SELECT loaded FROM snapshot_artifact WHERE snapshot_id=? AND artifact_id=?", snapshot, id);
        if (prev != null && (prev == 1 || !loaded)) return;
        db.update("INSERT OR REPLACE INTO snapshot_artifact(snapshot_id, artifact_id, loaded) VALUES(?,?,?)", snapshot, id, loaded ? 1 : 0);
        for (long child : db.query("SELECT child_id FROM artifact_nested WHERE parent_id=?", rs -> rs.getLong(1), id)) {
            addWithChildren(snapshot, child, loaded && isLoaded(child, keys), keys, seen);
        }
    }

    /** Set per snapshot: on a server, mods declaring {@code "environment": "client"} are never loaded (weak spot 13). */
    private boolean server = true;

    private boolean isLoaded(long artifactId, Set<String> keys) throws SQLException {
        if (server && db.queryInt("SELECT count(*) FROM artifact WHERE id=? AND "
                + dev.sevli.query.Scope.CLIENT_ONLY_SQL.replace("{a}", "artifact"), artifactId) > 0) return false;
        if (keys.isEmpty()) return true;
        String modId = db.queryString("SELECT mod_id FROM artifact WHERE id=?", artifactId);
        if (modId == null) return true; // plain library: follows its parent
        String version = db.queryString("SELECT mod_version FROM artifact WHERE id=?", artifactId);
        return keys.contains(modId + " " + LoaderLog.normalizeVersion(version));
    }

    /** The loader's list also names java, minecraft and fabricloader versions; current copy for people (history: snapshot_loaded). */
    private void writeLoaderList(String env, List<LoaderLog.LoadedMod> loaded) throws IOException {
        if (loaded.isEmpty()) return;
        Path f = home.resolve("envs").resolve(env).resolve("loaded-mods.txt");
        Files.createDirectories(f.getParent());
        Files.write(f, loaded.stream().map(m -> (m.nested() ? "  " : "") + m.id() + " " + m.version()).toList());
    }

    /**
     * Reads the source's config/datapack text, redacts secrets and stores each distinct file once under
     * {@code texts/<sha>}. Files whose size and mtime are unchanged since a previous read are not read again.
     */
    /** A snapshot's config and datapack texts in one file, for {@code grep scope=config} ({@link dev.sevli.store.Packs}). */
    public static Path textPack(Path home, long snapshotId) {
        return home.resolve("texts").resolve("snapshot-" + snapshotId + ".pack");
    }

    private void packTexts(long snapshotId) throws IOException, SQLException {
        Path pack = textPack(home, snapshotId);
        if (Files.exists(pack)) return;
        Map<String, Path> files = new java.util.TreeMap<>();
        for (String[] r : db.query("SELECT rel_path, sha256 FROM snapshot_text WHERE snapshot_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, snapshotId)) {
            files.put(r[0], home.resolve("texts").resolve(r[1].substring(0, 2)).resolve(r[1]));
        }
        if (!files.isEmpty()) dev.sevli.store.Packs.write(pack, files);
    }

    private Map<String, TextRef> storeTexts(EnvironmentSource source) throws IOException, SQLException {
        Map<String, TextRef> out = new TreeMap<>();
        List<EnvironmentSource.Entry> toRead = new ArrayList<>();
        List<EnvironmentSource.Entry> entries = new ArrayList<>();
        for (String dir : TEXT_DIRS) entries.addAll(source.listTree(dir, MAX_TEXT_BYTES));
        for (EnvironmentSource.Entry e : entries) {
            String rel = e.relPath();
            String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!TEXT_EXT.contains(ext) || SecretFilter.isSecretFile(rel)) continue;
            String sha = cachedHash(source.describe() + "|text|" + rel, e);
            if (sha != null && Files.exists(textPath(sha))) out.put(rel, new TextRef(sha, e.size()));
            else toRead.add(e);
        }
        for (int i = 0; i < toRead.size(); i += 200) {
            List<EnvironmentSource.Entry> batch = toRead.subList(i, Math.min(toRead.size(), i + 200));
            Map<String, byte[]> data = source.readMany(batch.stream().map(EnvironmentSource.Entry::relPath).toList());
            for (EnvironmentSource.Entry e : batch) {
                byte[] raw = data.get(e.relPath());
                if (raw == null) continue;
                TextRef t = storeText(raw);
                rememberHash(source.describe() + "|text|" + e.relPath(), e, t.sha256());
                out.put(e.relPath(), t);
            }
        }
        byte[] props = source.readIfExists("server.properties"); // small; always re-read so its redaction stays current
        if (props != null) out.put("server.properties", storeText(props));
        return out;
    }

    private TextRef storeText(byte[] raw) throws IOException {
        byte[] redacted = SecretFilter.redact(new String(raw, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String sha = JarParser.sha256(redacted);
        writeAtomically(textPath(sha), redacted);
        return new TextRef(sha, redacted.length);
    }

    /** Where a stored (redacted) text file lives. */
    public Path textPath(String sha) {
        return home.resolve("texts").resolve(sha.substring(0, 2)).resolve(sha);
    }

    /** Keeps envs/<env>/files as a plain copy of the current snapshot's text, for people who browse it. */
    private void refreshMirror(String env, Map<String, TextRef> texts) throws IOException {
        Path root = home.resolve("envs").resolve(env).resolve("files");
        Path manifestFile = home.resolve("envs").resolve(env).resolve("files.json");
        Gson gson = new Gson();
        Map<String, String> manifest = Files.exists(manifestFile)
                ? gson.fromJson(Files.readString(manifestFile), new TypeToken<Map<String, String>>() {}.getType())
                : new HashMap<>();
        Map<String, String> next = new TreeMap<>();
        for (var t : texts.entrySet()) {
            Path target = root.resolve(t.getKey()).normalize();
            if (!target.startsWith(root)) continue;
            next.put(t.getKey(), t.getValue().sha256());
            if (!t.getValue().sha256().equals(manifest.get(t.getKey())) || !Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Files.copy(textPath(t.getValue().sha256()), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String old : manifest.keySet()) {
            if (!next.containsKey(old)) Files.deleteIfExists(root.resolve(old));
        }
        Files.createDirectories(manifestFile.getParent());
        Files.writeString(manifestFile, gson.toJson(next));
    }

    /**
     * Indexes a project's built jar like any mod jar, so {@code project:} queries can use it in place of the deployed
     * copy. Builds are not part of any snapshot; only the newest {@link #KEPT_BUILDS} unreferenced builds of a mod are
     * kept (copied version folders of one mod share its id), older ones are removed with their caches.
     */
    public long indexBuild(String env, Path jar, String modId, Consumer<String> progress) throws IOException, SQLException {
        ensureBase(def(env), progress);
        byte[] bytes = Files.readAllBytes(jar);
        String sha = JarParser.sha256(bytes);
        Path local = indexer.artifactPath(sha);
        writeAtomically(local, bytes);
        var results = indexer.ensureAll(List.of(new Indexer.Job(local, sha, "mod")), progress);
        if (results.isEmpty()) throw new IllegalStateException("could not index " + jar.getFileName());
        Indexer.Result r = results.values().iterator().next();
        if (r.newlyIndexed()) db.update("UPDATE artifact SET file_name=? WHERE id=?", jar.getFileName().toString(), r.artifactId());
        if (modId != null) pruneBuilds(modId, r.artifactId(), progress);
        return r.artifactId();
    }

    static final int KEPT_BUILDS = 3;

    private void pruneBuilds(String modId, long keep, Consumer<String> progress) throws SQLException, IOException {
        List<Object[]> builds = db.query("""
                SELECT a.id, a.sha256, a.file_name FROM artifact a WHERE a.mod_id=? AND a.id<>?
                  AND NOT EXISTS (SELECT 1 FROM snapshot_artifact sa WHERE sa.artifact_id=a.id)
                  AND NOT EXISTS (SELECT 1 FROM artifact_nested n WHERE n.child_id=a.id)
                ORDER BY a.id DESC""", rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, modId, keep);
        for (Object[] b : builds.subList(Math.min(builds.size(), KEPT_BUILDS - 1), builds.size())) {
            long id = (long) b[0];
            String sha = (String) b[1];
            db.inTransaction(() -> {
                db.update("DELETE FROM artifact_nested WHERE parent_id=?", id);
                db.update("DELETE FROM artifact WHERE id=?", id); // classes, members, refs, mixins, resources cascade
            });
            Files.deleteIfExists(indexer.artifactPath(sha));
            deleteTree(indexer.resourceRoot(sha));
            for (String cache : List.of("remapped", "decomp")) { // per-jar caches are named <sha prefix>-...
                Path dir = home.resolve(cache);
                if (!Files.isDirectory(dir)) continue;
                try (var list = Files.list(dir)) {
                    for (Path f : list.filter(f -> f.getFileName().toString().startsWith(sha.substring(0, 16) + "-")).toList()) deleteTree(f);
                }
            }
            progress.accept("removed old build " + b[2]);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }

    /** Content-addressed write: temp file, then rename, so a killed process never leaves a partial file under a hash. */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target)) return; // same hash, same bytes
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException e) { // another process stored it first
            Files.deleteIfExists(tmp);
        }
    }

    /** Mod-level diff (by mod id) between this snapshot and the env's previous one. */
    public String diffWithPrevious(String env, long snapshot) throws SQLException {
        Long prev = db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND kind='sync' AND id<?", env, snapshot);
        if (prev == null) return "# " + env + ": first snapshot (" + snapshot + "), nothing to compare\n";
        return diff(prev, snapshot);
    }

    public String diff(long from, long to) throws SQLException {
        Map<String, String> a = topLevelMods(from);
        Map<String, String> b = topLevelMods(to);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (var e : b.entrySet()) {
            String old = a.get(e.getKey());
            if (old == null) added.add(e.getKey() + " " + e.getValue());
            else if (!old.equals(e.getValue())) changed.add(e.getKey() + " " + old + " -> " + e.getValue());
        }
        for (var e : a.entrySet()) if (!b.containsKey(e.getKey())) removed.add(e.getKey() + " " + e.getValue());
        StringBuilder sb = new StringBuilder("# Snapshot " + from + " -> " + to + "\n");
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return sb.append("No mod changes.\n").toString();
        section(sb, "Added", added);
        section(sb, "Removed", removed);
        section(sb, "Updated", changed);
        return sb.toString();
    }

    private Map<String, String> topLevelMods(long snapshot) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        for (String[] r : db.query("""
                SELECT coalesce(a.mod_id, sf.rel_path), coalesce(a.mod_version, a.sha256)
                FROM snapshot_file sf JOIN artifact a ON a.sha256 = sf.sha256 WHERE sf.snapshot_id=?""",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, snapshot)) {
            out.put(r[0], r[1]);
        }
        return out;
    }

    private static void section(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) return;
        sb.append("\n## ").append(title).append(" (").append(items.size()).append(")\n");
        items.stream().sorted().forEach(i -> sb.append("- ").append(i).append('\n'));
    }

    private String cachedHash(String key, EnvironmentSource.Entry e) throws SQLException {
        return db.queryString("SELECT sha256 FROM file_hash WHERE path=? AND size=? AND mtime=?", key, e.size(), e.mtime());
    }

    private void rememberHash(String key, EnvironmentSource.Entry e, String sha) throws SQLException {
        db.update("INSERT OR REPLACE INTO file_hash(path, size, mtime, sha256) VALUES(?,?,?,?)", key, e.size(), e.mtime(), sha);
    }

    private String hashLocal(Path file) throws IOException, SQLException {
        var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        String key = "local|" + file.toAbsolutePath();
        String cached = db.queryString("SELECT sha256 FROM file_hash WHERE path=? AND size=? AND mtime=?",
                key, attrs.size(), attrs.lastModifiedTime().toMillis());
        if (cached != null) return cached;
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        String sha = HexFormat.of().formatHex(md.digest());
        db.update("INSERT OR REPLACE INTO file_hash(path, size, mtime, sha256) VALUES(?,?,?,?)",
                key, attrs.size(), attrs.lastModifiedTime().toMillis(), sha);
        return sha;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EnvironmentSource.Entry withSha(EnvironmentSource.Entry e, String sha) {
        return new EnvironmentSource.Entry(e.relPath(), e.size(), e.mtime(), sha);
    }

    /** The current snapshot: the latest one from the live source (imports are history), or null if never synced. */
    public Long latestSnapshot(String env) throws SQLException {
        Long id = db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND kind='sync'", env);
        return id != null ? id : db.queryLong("SELECT max(id) FROM snapshot WHERE env=?", env);
    }

    /** A snapshot by id, label or "current". */
    public long resolve(String env, String ref) throws SQLException {
        if (ref == null || ref.equals("current")) {
            Long id = latestSnapshot(env);
            if (id == null) throw new IllegalArgumentException("never synced: " + env);
            return id;
        }
        if (ref.chars().allMatch(Character::isDigit)) return Long.parseLong(ref);
        Long id = db.queryLong("SELECT max(id) FROM snapshot WHERE env=? AND label=?", env, ref);
        if (id == null) throw new IllegalArgumentException("No snapshot of " + env + " labeled '" + ref + "'. Known: " + labels(env));
        return id;
    }

    /** Distinct labels, oldest first. */
    public List<String> labels(String env) throws SQLException {
        return db.query("SELECT label FROM snapshot WHERE env=? AND label IS NOT NULL GROUP BY label ORDER BY min(id)",
                rs -> rs.getString(1), env);
    }

    public Map<String, Long> latestSnapshots() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String env : config.environments.keySet()) out.put(env, latestSnapshot(env));
        return out;
    }
}
