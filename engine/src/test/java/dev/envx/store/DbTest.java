package dev.envx.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema upgrades keep old data; an index from a newer envx is refused instead of misread. */
class DbTest {
    private static void raw(Path home, String... sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("index.sqlite")); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        }
    }

    @Test
    void aVersion1IndexIsUpgradedInPlace(@TempDir Path home) throws Exception {
        raw(home, "CREATE TABLE snapshot(id INTEGER PRIMARY KEY, env TEXT NOT NULL, taken_at TEXT NOT NULL, source TEXT NOT NULL,"
                        + " loader_list INTEGER NOT NULL DEFAULT 0)",
                "INSERT INTO snapshot(env, taken_at, source) VALUES('fx', '2026-01-01T00:00:00Z', 'folder')",
                "PRAGMA user_version=1");
        try (Db db = Db.open(home, false)) {
            assertEquals(2, db.queryInt("PRAGMA user_version"));
            assertEquals("sync", db.queryString("SELECT kind FROM snapshot WHERE env='fx'")); // old rows are live syncs
            assertEquals(1, db.queryInt("SELECT count(*) FROM pragma_table_info('snapshot') WHERE name='label'"));
        }
        try (Db db = Db.open(home, true)) { // agents open read-only
            assertEquals(1, db.queryInt("SELECT count(*) FROM snapshot"));
        }
    }

    @Test
    void anIndexFromANewerEnvxIsRefused(@TempDir Path home) throws Exception {
        raw(home, "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)", "PRAGMA user_version=99");
        SQLException e = assertThrows(SQLException.class, () -> Db.open(home, true));
        assertTrue(e.getMessage().contains("newer envx"), e.getMessage());
        assertThrows(SQLException.class, () -> Db.open(home, false));
    }
}
