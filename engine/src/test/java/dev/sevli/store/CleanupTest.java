package dev.sevli.store;

import dev.sevli.Config;
import dev.sevli.env.Environments;
import dev.sevli.index.JarParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional cleanup (ADR 0014): previewed, only unused data, only inside the data home, never the server's files. */
class CleanupTest {
    @TempDir
    Path tmp;
    private String previousHome;
    private byte[] shared, onlyOld;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sevli.home");
        System.setProperty("sevli.home", tmp.resolve("home").toString());
        Cleanup.Trash.disabled = true; // tests never touch the real Recycle Bin
        Method base = Class.forName("dev.sevli.Fixture").getDeclaredMethod("base", Path.class);
        base.setAccessible(true);
        base.invoke(null, tmp.resolve("home").resolve("base").resolve("minecraft-1.20.1-yarn-1.20.1+build.10"));
        Method mod = Class.forName("dev.sevli.Fixture").getDeclaredMethod("mod", String.class, String.class, String.class, String.class);
        mod.setAccessible(true);
        shared = (byte[]) mod.invoke(null, "demo", "1.0.0", "dev/demo/mixin/LivingMixin", "demo$onTick");
        onlyOld = (byte[]) mod.invoke(null, "other", "2.0.0", "dev/other/mixin/TickMixin", "other$tick");
    }

    @AfterEach
    void restore() {
        if (previousHome == null) System.clearProperty("sevli.home");
        else System.setProperty("sevli.home", previousHome);
    }

    private Path server(String name, byte[]... jars) throws Exception {
        Path s = tmp.resolve(name);
        Files.createDirectories(s.resolve("mods"));
        for (byte[] j : jars) Files.write(s.resolve("mods").resolve(JarParser.modIdVersion(j)[0] + ".jar"), j);
        Files.createDirectories(s.resolve("config"));
        Files.writeString(s.resolve("config/demo.json"), "{\"radius\": 8}");
        return s;
    }

    private static Map<String, String> files(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) out.put(dir.relativize(p).toString(), JarParser.sha256(Files.readAllBytes(p)));
        }
        return out;
    }

    private Config twoServers(Path oldServer, Path keptServer) throws Exception {
        Config config = Config.load();
        config.supportPolicy = "all";
        config.autoSyncHours = 0;
        for (var e : Map.of("old", oldServer, "kept", keptServer).entrySet()) {
            Config.EnvDef def = new Config.EnvDef();
            def.sources.add(e.getValue().toString());
            config.environments.put(e.getKey(), def);
        }
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            Environments envs = new Environments(config, db);
            envs.sync("old", m -> {});
            envs.sync("kept", m -> {});
        }
        return config;
    }

    @Test
    void removingAServerKeepsItsHistoryAndNeverTouchesTheServer() throws Exception {
        Path oldServer = server("old-server", shared, onlyOld);
        Config config = twoServers(oldServer, server("kept-server", shared));
        Map<String, String> before = files(oldServer);
        assertTrue(Files.isDirectory(config.home().resolve("envs/old")));
        dev.sevli.cli.MainAccess.remove(config, List.of("old", "--yes"));
        Config after = Config.load();
        assertFalse(after.environments.containsKey("old"));
        assertFalse(Files.exists(config.home().resolve("envs/old")), "Sevli's own copies for it are gone");
        assertEquals(before, files(oldServer), "the server's files are byte-for-byte unchanged");
        try (Db db = Db.open(config.home(), true)) {
            assertTrue(db.queryInt("SELECT count(*) FROM snapshot WHERE env='old'") > 0, "its history stays");
            assertEquals(1, db.queryInt("SELECT count(*) FROM artifact WHERE sha256=?", JarParser.sha256(onlyOld)), "its jar data stays");
        }
    }

    @Test
    void cleanOffersOnlyWhatNothingUsesAndFreesOnlyThat() throws Exception {
        Path oldServer = server("old-server", shared, onlyOld);
        Config config = twoServers(oldServer, server("kept-server", shared));
        config.environments.remove("old");
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            Cleanup c = new Cleanup(config, db);
            List<Cleanup.Item> items = c.preview(List.of(), false);
            assertEquals(1, items.size(), items.toString());
            Cleanup.Item history = items.getFirst();
            assertTrue(history.historyLoss());
            assertEquals(List.of("other 2.0.0"), history.jars().stream().map(Cleanup.Jar::name).toList(), "demo is still used by 'kept'");
            assertTrue(history.bytes() > 0);
            Path otherJar = config.home().resolve("artifacts").resolve(JarParser.sha256(onlyOld).substring(0, 2)).resolve(JarParser.sha256(onlyOld) + ".jar");
            assertTrue(Files.exists(otherJar));
            assertTrue(c.free(items, m -> {}) > 0);
            assertFalse(Files.exists(otherJar));
            assertEquals(0, db.queryInt("SELECT count(*) FROM snapshot WHERE env='old'"));
            assertEquals(0, db.queryInt("SELECT count(*) FROM artifact WHERE sha256=?", JarParser.sha256(onlyOld)));
            assertEquals(1, db.queryInt("SELECT count(*) FROM artifact WHERE sha256=?", JarParser.sha256(shared)), "shared jar data stays");
            assertTrue(db.queryInt("SELECT count(*) FROM snapshot WHERE env='kept'") > 0);
            assertEquals(List.of(), c.preview(List.of(), false), "nothing else to offer");
        }
    }

    @Test
    void aServersCurrentSnapshotIsNeverOfferedAndNothingOutsideTheHomeIsTouched() throws Exception {
        Path oldServer = server("old-server", shared, onlyOld);
        Config config = twoServers(oldServer, server("kept-server", shared));
        try (Db db = Db.open(config.home(), false)) {
            Cleanup c = new Cleanup(config, db);
            long current = db.queryLong("SELECT max(id) FROM snapshot WHERE env='kept'");
            var e = assertThrows(IllegalArgumentException.class, () -> c.preview(List.of("kept#" + current), false));
            assertTrue(e.getMessage().contains("never freed"), e.getMessage());
            assertThrows(java.io.IOException.class, () -> c.trash(oldServer.resolve("mods")));
            assertTrue(Files.exists(oldServer.resolve("mods")));
        }
    }

    @Test
    void freeingANamedPastSnapshotKeepsTheCurrentOne() throws Exception {
        Path s = server("old-server", shared, onlyOld);
        Config config = twoServers(s, server("kept-server", shared));
        Files.delete(s.resolve("mods/other.jar")); // the server moves on: a new current snapshot without it
        try (Db db = Db.open(config.home(), false)) {
            long past = db.queryLong("SELECT max(id) FROM snapshot WHERE env='old'");
            new Environments(config, db).sync("old", m -> {});
            Cleanup c = new Cleanup(config, db);
            List<Cleanup.Item> items = c.preview(List.of("old#" + past), false);
            assertEquals(List.of("other 2.0.0"), items.getFirst().jars().stream().map(Cleanup.Jar::name).toList());
            c.free(items, m -> {});
            assertEquals(1, db.queryInt("SELECT count(*) FROM snapshot WHERE env='old'"), "the current snapshot stays");
        }
    }
}
