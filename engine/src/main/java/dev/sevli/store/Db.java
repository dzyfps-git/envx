package dev.sevli.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The single SQLite index. WAL mode lets any number of read-only MCP processes query while one
 * CLI process indexes, so v1 needs no daemon (see docs/adr/0002-no-daemon-yet.md).
 *
 * <p>Identity rules: an artifact is a jar identified by SHA-256 and indexed once, whatever
 * environment references it. Minecraft names are stored in intermediary ({@code name}) with the
 * display flavour alongside ({@code named}).
 */
public final class Db implements AutoCloseable {
    /** Bump when the index content changes shape; artifacts with an older version are re-indexed. */
    public static final int INDEX_VERSION = 1;
    private static final int SCHEMA_VERSION = 3;

    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    /**
     * Opens the index. A sync in another process may be checkpointing the WAL at that moment, which Windows reports
     * as a transient I/O error ({@code SQLITE_IOERR_TRUNCATE}) to a reader opening the file; such opens are retried.
     */
    public static Db open(Path home, boolean readOnly) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return openOnce(home, readOnly);
            } catch (SQLException e) {
                String m = String.valueOf(e.getMessage());
                boolean transientError = m.contains("SQLITE_IOERR") || m.contains("SQLITE_BUSY") || m.contains("database is locked");
                if (!transientError || attempt >= 6) throw e;
                try {
                    Thread.sleep(150L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static Db openOnce(Path home, boolean readOnly) throws SQLException {
        Path file = home.resolve("index.sqlite");
        if (!readOnly) {
            try {
                java.nio.file.Files.createDirectories(home); // the data home is created by the first write, not before
            } catch (java.io.IOException e) {
                throw new SQLException("cannot create " + home + ": " + e.getMessage(), e);
            }
        }
        var cfg = new org.sqlite.SQLiteConfig();
        cfg.setReadOnly(readOnly);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), cfg.toProperties());
        int v;
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            if (!readOnly) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            s.execute("PRAGMA cache_size=-65536");
            try (var rs = s.executeQuery("PRAGMA user_version")) {
                v = rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        Db db = new Db(c);
        if (v > SCHEMA_VERSION) { // an older sevli must not read, or write into, a newer layout
            c.close();
            throw new SQLException("The index at " + file + " was written by a newer sevli (schema v" + v + "); sevli "
                    + dev.sevli.Version.VALUE + " reads up to v" + SCHEMA_VERSION + ". Start a new agent session or run the newer sevli.");
        }
        if (!readOnly) db.migrate();
        return db;
    }

    public Connection conn() {
        return conn;
    }

    private void migrate() throws SQLException {
        int v = queryInt("PRAGMA user_version");
        if (v >= SCHEMA_VERSION) return;
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS artifact(
                  id INTEGER PRIMARY KEY,
                  sha256 TEXT NOT NULL UNIQUE,
                  kind TEXT NOT NULL,            -- minecraft | mod | library
                  file_name TEXT NOT NULL,
                  size INTEGER NOT NULL,
                  mod_id TEXT, mod_version TEXT, mod_name TEXT,
                  meta_json TEXT,                -- compact fabric.mod.json subset
                  index_version INTEGER NOT NULL DEFAULT 0)""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS artifact_mod ON artifact(mod_id)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS artifact_nested(      -- jar-in-jar; one library may be nested in many mods
                  parent_id INTEGER NOT NULL REFERENCES artifact(id),
                  child_id INTEGER NOT NULL REFERENCES artifact(id),
                  PRIMARY KEY(parent_id, child_id)) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS class(
                  id INTEGER PRIMARY KEY,
                  artifact_id INTEGER NOT NULL REFERENCES artifact(id) ON DELETE CASCADE,
                  name TEXT NOT NULL,            -- internal name as it exists at runtime (intermediary for MC)
                  named TEXT NOT NULL,           -- display flavour (Yarn)
                  simple TEXT NOT NULL,          -- lower-case simple display name, for lookup
                  super TEXT, interfaces TEXT, access INTEGER NOT NULL,
                  is_mixin INTEGER NOT NULL DEFAULT 0)""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_name ON class(name)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_named ON class(named COLLATE NOCASE)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_simple ON class(simple)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_artifact ON class(artifact_id)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_super ON class(super)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS member(
                  class_id INTEGER NOT NULL REFERENCES class(id) ON DELETE CASCADE,
                  kind TEXT NOT NULL,            -- m | f
                  name TEXT NOT NULL, descriptor TEXT NOT NULL,
                  named TEXT NOT NULL, named_desc TEXT NOT NULL,
                  access INTEGER NOT NULL,
                  line INTEGER,                  -- first bytecode line number, when present
                  PRIMARY KEY(class_id, kind, name, descriptor)) WITHOUT ROWID""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS member_name ON member(name)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS member_named ON member(named COLLATE NOCASE)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS class_ref(
                  owner TEXT NOT NULL,           -- referenced class (runtime name)
                  class_id INTEGER NOT NULL REFERENCES class(id) ON DELETE CASCADE,
                  PRIMARY KEY(owner, class_id)) WITHOUT ROWID""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS class_ref_class ON class_ref(class_id)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS mixin(
                  id INTEGER PRIMARY KEY,
                  artifact_id INTEGER NOT NULL REFERENCES artifact(id) ON DELETE CASCADE,
                  config TEXT NOT NULL, mixin_class TEXT NOT NULL,
                  target_class TEXT NOT NULL,    -- runtime name
                  kind TEXT NOT NULL,            -- Inject | Redirect | WrapOperation | Overwrite | Accessor | ... | class
                  handler TEXT,
                  target_raw TEXT, target_name TEXT, target_desc TEXT,
                  at_value TEXT, at_target TEXT,
                  priority INTEGER NOT NULL DEFAULT 1000,
                  cancellable INTEGER NOT NULL DEFAULT 0,
                  env_side TEXT,                 -- common | server | client
                  plugin TEXT)""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS mixin_target ON mixin(target_class, target_name)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS mixin_artifact ON mixin(artifact_id)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS resource(
                  artifact_id INTEGER NOT NULL REFERENCES artifact(id) ON DELETE CASCADE,
                  path TEXT NOT NULL, size INTEGER NOT NULL,
                  PRIMARY KEY(artifact_id, path)) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot(
                  id INTEGER PRIMARY KEY,
                  env TEXT NOT NULL, taken_at TEXT NOT NULL, source TEXT NOT NULL,
                  loader_list INTEGER NOT NULL DEFAULT 0)   -- 1 when latest.log gave the resolved mod list""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS snapshot_env ON snapshot(env, id)");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_file(
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  rel_path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL,
                  PRIMARY KEY(snapshot_id, rel_path)) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_artifact(
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  artifact_id INTEGER NOT NULL REFERENCES artifact(id),
                  loaded INTEGER NOT NULL DEFAULT 1,
                  PRIMARY KEY(snapshot_id, artifact_id)) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_hash(
                  path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, sha256 TEXT NOT NULL) WITHOUT ROWID""");
            // v2: history. Snapshots carry a pack identity and label; config/datapack text and the loader's mod
            // list are kept per snapshot (text by hash under texts/), so any past snapshot can be queried.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_text(
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  rel_path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL,
                  PRIMARY KEY(snapshot_id, rel_path)) WITHOUT ROWID""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_loaded(      -- the loader's resolved list (includes java, minecraft, loader)
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  mod_id TEXT NOT NULL, version TEXT NOT NULL, nested INTEGER NOT NULL,
                  PRIMARY KEY(snapshot_id, mod_id, version)) WITHOUT ROWID""");
            for (String col : List.of("kind TEXT NOT NULL DEFAULT 'sync'", // sync (live source) | import (a past pack folder)
                    "label TEXT", "pack TEXT", "pack_name TEXT", "checked_at TEXT", "fingerprint TEXT")) {
                String name = col.substring(0, col.indexOf(' '));
                if (queryInt("SELECT count(*) FROM pragma_table_info('snapshot') WHERE name=?", name) == 0) {
                    s.executeUpdate("ALTER TABLE snapshot ADD COLUMN " + col);
                }
            }
            s.executeUpdate("CREATE INDEX IF NOT EXISTS snapshot_label ON snapshot(env, label)");
            // v3: supported-only indexing (ADR 0014). Jars on a server that were hashed but not indexed, and why.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshot_unindexed(
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  rel_path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL,
                  mod_id TEXT, version TEXT,
                  reason TEXT NOT NULL,          -- not_supported | newly_supported | revoked
                  PRIMARY KEY(snapshot_id, rel_path)) WITHOUT ROWID""");
            s.executeUpdate("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    public String meta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMeta(String key, String value) throws SQLException {
        update("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    public int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args)) {
            return ps.executeUpdate();
        }
    }

    public int queryInt(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Long queryLong(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }
    }

    public String queryString(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Runs a query and maps each row; convenient for small result sets. */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapper.map(rs));
        }
        return out;
    }

    public PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }

    public void inTransaction(SqlRunnable r) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            r.run();
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
