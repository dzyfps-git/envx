package dev.envx.index;

import dev.envx.mapping.Mappings;
import dev.envx.store.Db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Makes sure jars are in the index. Jars are identified by SHA-256, so a jar already indexed for
 * another environment (or nested in another mod) costs nothing. Parsing runs in parallel; writing
 * is single-threaded because SQLite has one writer.
 */
public final class Indexer {
    private final Db db;
    private final Path home;
    private final JarParser parser;

    public Indexer(Db db, Path home, Mappings mappings) {
        this.db = db;
        this.home = home;
        this.parser = new JarParser(mappings);
    }

    /** A jar to index: where to read it and, if already known, its hash (to skip reading it). */
    public record Job(Path file, String sha256, String kind) {}

    public record Result(long artifactId, String sha256, boolean newlyIndexed, List<String> warnings) {}

    public Map<Path, Result> ensureAll(List<Job> jobs, Consumer<String> progress) throws SQLException {
        Map<Path, Result> results = new LinkedHashMap<>();
        List<Job> todo = new ArrayList<>();
        for (Job j : jobs) {
            Long id = j.sha256() == null ? null : currentArtifact(j.sha256());
            if (id != null) results.put(j.file(), new Result(id, j.sha256(), false, List.of()));
            else todo.add(j);
        }
        if (todo.isEmpty()) return results;

        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 6));
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            // Submit in windows so at most ~2x threads parsed jars are held in memory at once.
            int window = threads * 2;
            List<Future<ParsedJar>> inFlight = new ArrayList<>();
            int next = 0;
            int done = 0;
            while (done < todo.size()) {
                while (next < todo.size() && inFlight.size() < window) {
                    Job j = todo.get(next++);
                    inFlight.add(pool.submit(() -> parser.parse(j.file().getFileName().toString(), Files.readAllBytes(j.file()), j.kind())));
                }
                Future<ParsedJar> f = inFlight.removeFirst();
                Job j = todo.get(done++);
                ParsedJar pj;
                try {
                    pj = f.get();
                } catch (Exception e) {
                    progress.accept("FAILED " + j.file().getFileName() + ": " + e.getCause());
                    continue;
                }
                long id = write(pj);
                results.put(j.file(), new Result(id, pj.sha256, true, pj.warnings));
                progress.accept("[" + done + "/" + todo.size() + "] " + pj.fileName + " (" + pj.classes.size() + " classes, "
                        + pj.mixins.size() + " mixin targets)");
            }
        }
        return results;
    }

    /** Returns the artifact id when this hash is indexed at the current index version. */
    public Long currentArtifact(String sha) throws SQLException {
        return db.queryLong("SELECT id FROM artifact WHERE sha256=? AND index_version=?", sha, Db.INDEX_VERSION);
    }

    public Path artifactPath(String sha) {
        return home.resolve("artifacts").resolve(sha.substring(0, 2)).resolve(sha + ".jar");
    }

    public Path resourceRoot(String sha) {
        return home.resolve("resources").resolve(sha);
    }

    private long write(ParsedJar pj) throws SQLException {
        long[] id = new long[1];
        db.inTransaction(() -> id[0] = writeOne(pj));
        return id[0];
    }

    private long writeOne(ParsedJar pj) throws SQLException {
        Long existing = db.queryLong("SELECT id FROM artifact WHERE sha256=?", pj.sha256);
        if (existing != null && db.queryInt("SELECT index_version FROM artifact WHERE id=?", existing) == Db.INDEX_VERSION) {
            return existing; // e.g. a library nested in several mods
        }
        storeBytes(pj);
        long id;
        if (existing != null) {
            id = existing;
            for (String t : List.of("mixin", "resource", "class")) db.update("DELETE FROM " + t + " WHERE artifact_id=?", id);
            db.update("DELETE FROM artifact_nested WHERE parent_id=?", id);
        } else {
            db.update("INSERT INTO artifact(sha256, kind, file_name, size) VALUES(?,?,?,?)", pj.sha256, pj.kind, pj.fileName, pj.size);
            id = db.queryLong("SELECT id FROM artifact WHERE sha256=?", pj.sha256);
        }
        ParsedJar.ModMeta mod = pj.mod;
        db.update("UPDATE artifact SET kind=?, file_name=?, size=?, mod_id=?, mod_version=?, mod_name=?, meta_json=? WHERE id=?",
                pj.kind, pj.fileName, pj.size, mod == null ? null : mod.id(), mod == null ? null : mod.version(),
                mod == null ? null : mod.name(), mod == null ? null : mod.json(), id);

        writeClasses(id, pj.classes);
        writeMixins(id, pj.mixins);
        writeResources(id, pj);
        for (ParsedJar n : pj.nested) {
            long child = writeOne(n);
            db.update("INSERT OR IGNORE INTO artifact_nested(parent_id, child_id) VALUES(?,?)", id, child);
        }
        // Marked current last, so an interrupted run is simply redone next time.
        db.update("UPDATE artifact SET index_version=? WHERE id=?", Db.INDEX_VERSION, id);
        return id;
    }

    private void writeClasses(long artifactId, List<ParsedJar.ClassRec> classes) throws SQLException {
        var c = db.conn();
        try (PreparedStatement cls = c.prepareStatement(
                "INSERT INTO class(artifact_id, name, named, simple, super, interfaces, access, is_mixin) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement mem = c.prepareStatement(
                     "INSERT OR IGNORE INTO member(class_id, kind, name, descriptor, named, named_desc, access, line) VALUES(?,?,?,?,?,?,?,?)");
             PreparedStatement ref = c.prepareStatement("INSERT OR IGNORE INTO class_ref(owner, class_id) VALUES(?,?)")) {
            for (ParsedJar.ClassRec r : classes) {
                cls.setLong(1, artifactId);
                cls.setString(2, r.name());
                cls.setString(3, r.named());
                cls.setString(4, simpleName(r.named()));
                cls.setString(5, r.superName());
                cls.setString(6, r.interfaces());
                cls.setInt(7, r.access());
                cls.setInt(8, r.mixin() ? 1 : 0);
                cls.executeUpdate();
                long classId;
                try (ResultSet keys = cls.getGeneratedKeys()) {
                    keys.next();
                    classId = keys.getLong(1);
                }
                for (ParsedJar.MemberRec m : r.members()) {
                    mem.setLong(1, classId);
                    mem.setString(2, String.valueOf(m.kind()));
                    mem.setString(3, m.name());
                    mem.setString(4, m.desc());
                    mem.setString(5, m.named());
                    mem.setString(6, m.namedDesc());
                    mem.setInt(7, m.access());
                    mem.setObject(8, m.line());
                    mem.addBatch();
                }
                for (String owner : r.refs()) {
                    ref.setString(1, owner);
                    ref.setLong(2, classId);
                    ref.addBatch();
                }
            }
            mem.executeBatch();
            ref.executeBatch();
        }
    }

    private void writeMixins(long artifactId, List<ParsedJar.MixinRec> mixins) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("""
                INSERT INTO mixin(artifact_id, config, mixin_class, target_class, kind, handler, target_raw, target_name,
                  target_desc, at_value, at_target, priority, cancellable, env_side, plugin)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            for (ParsedJar.MixinRec m : mixins) {
                Object[] v = {artifactId, m.config(), m.mixinClass(), m.targetClass(), m.kind(), m.handler(), m.targetRaw(),
                        m.targetName(), m.targetDesc(), m.atValue(), m.atTarget(), m.priority(), m.cancellable() ? 1 : 0,
                        m.side(), m.plugin()};
                for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeResources(long artifactId, ParsedJar pj) throws SQLException {
        Path root = resourceRoot(pj.sha256);
        try (PreparedStatement ps = db.conn().prepareStatement("INSERT OR IGNORE INTO resource(artifact_id, path, size) VALUES(?,?,?)")) {
            for (ParsedJar.ResourceRec r : pj.resources) {
                ps.setLong(1, artifactId);
                ps.setString(2, r.path());
                ps.setLong(3, r.size());
                ps.addBatch();
                if (r.data() != null) {
                    Path target = root.resolve(r.path()).normalize();
                    if (!target.startsWith(root)) continue; // zip-slip guard
                    try {
                        Files.createDirectories(target.getParent());
                        Files.write(target, r.data());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            ps.executeBatch();
        }
    }

    private void storeBytes(ParsedJar pj) {
        Path target = artifactPath(pj.sha256);
        try {
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(pj.sha256 + ".tmp");
                Files.write(tmp, pj.bytes);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pj.bytes = null;
    }

    static String simpleName(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
}
