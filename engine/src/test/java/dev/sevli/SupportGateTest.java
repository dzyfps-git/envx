package dev.sevli;

import dev.sevli.catalog.Supported;
import dev.sevli.env.Environments;
import dev.sevli.index.JarParser;
import dev.sevli.store.Db;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Supported-only indexing (ADR 0014): a public install indexes only jars the signed catalog list supports. */
class SupportGateTest {
    @TempDir
    Path tmp;
    private String previousHome, previousKey;
    private String privateKey;
    private Path catalog;
    private byte[] demo, other, later, bad;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sevli.home");
        previousKey = System.getProperty("sevli.catalogKey");
        System.setProperty("sevli.home", tmp.resolve("home").toString());
        String[] keys = Supported.newKeyPair();
        System.setProperty("sevli.catalogKey", keys[0]);
        privateKey = keys[1];
        Fixture.base(tmp.resolve("home").resolve("base").resolve("minecraft-1.20.1-yarn-1.20.1+build.10"));
        catalog = tmp.resolve("catalog");
        Files.createDirectories(catalog);
        demo = Fixture.mod("demo", "1.0.0", "dev/demo/mixin/LivingMixin", "demo$onTick");
        other = Fixture.mod("other", "2.0.0", "dev/other/mixin/TickMixin", "other$tick");
        later = Fixture.mod("later", "3.0.0", "dev/later/mixin/LaterMixin", "later$tick");
        bad = Fixture.mod("bad", "0.1.0", "dev/bad/mixin/BadMixin", "bad$tick");
    }

    @AfterEach
    void restore() {
        if (previousHome == null) System.clearProperty("sevli.home");
        else System.setProperty("sevli.home", previousHome);
        if (previousKey == null) System.clearProperty("sevli.catalogKey");
        else System.setProperty("sevli.catalogKey", previousKey);
    }

    /** Publishes a signed supported list: {jar bytes, since, status}. */
    private void publish(int version, Object[]... jars) throws Exception {
        StringBuilder json = new StringBuilder("{\"schema\":1,\"catalogVersion\":" + version + ",\"jars\":{");
        for (int i = 0; i < jars.length; i++) {
            byte[] jar = (byte[]) jars[i][0];
            String[] idv = JarParser.modIdVersion(jar);
            json.append(i > 0 ? "," : "").append('"').append(JarParser.sha256(jar)).append("\":{\"modId\":\"").append(idv[0])
                    .append("\",\"version\":\"").append(idv[1]).append("\",\"packs\":[\"demo-pack\"],\"status\":\"").append(jars[i][2])
                    .append("\",\"since\":").append(jars[i][1]).append('}');
        }
        byte[] data = json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
        Files.write(catalog.resolve("supported.json"), data);
        Files.writeString(catalog.resolve("supported.json.sig"), Supported.sign(data, privateKey));
    }

    private Path server(String name, byte[]... jars) throws Exception {
        Path s = tmp.resolve(name);
        Files.createDirectories(s.resolve("mods"));
        for (byte[] j : jars) Files.write(s.resolve("mods").resolve(JarParser.modIdVersion(j)[0] + ".jar"), j);
        return s;
    }

    private Config config(String env, Path server) throws Exception {
        Config config = Config.load();
        config.catalogUrl = catalog.resolve("index.json").toUri().toString();
        config.autoSyncHours = 0;
        Config.EnvDef def = new Config.EnvDef();
        def.sources.add(server.toString());
        config.environments.put(env, def);
        config.save();
        return config;
    }

    private static List<String> unindexed(Db db, long snapshot) throws Exception {
        return db.query("SELECT mod_id || ':' || reason FROM snapshot_unindexed WHERE snapshot_id=? ORDER BY mod_id", rs -> rs.getString(1), snapshot);
    }

    private static boolean indexed(Db db, byte[] jar) throws Exception {
        return db.queryInt("SELECT count(*) FROM artifact WHERE sha256=?", JarParser.sha256(jar)) > 0;
    }

    @Test
    void onlySupportedJarsAreIndexedAndTheRestAreListed() throws Exception {
        publish(1, new Object[]{demo, 1, "active"}, new Object[]{bad, 1, "revoked"});
        Config config = config("mine", server("server", demo, other, bad));
        try (Db db = Db.open(config.home(), false)) {
            Environments.SyncResult r = new Environments(config, db).sync("mine", m -> {});
            assertTrue(r.summary().contains("1 of 3 jars indexed; 1 not supported yet: other 2.0.0; 1 withdrawn from support: bad 0.1.0 (withdrawn)"), r.summary());
            assertTrue(indexed(db, demo));
            assertFalse(indexed(db, other), "an unsupported jar is hashed, never indexed");
            assertFalse(Files.exists(config.home().resolve("artifacts").resolve(JarParser.sha256(other) + ".jar")), "nor stored");
            assertFalse(indexed(db, bad));
            assertEquals(List.of("bad:revoked", "other:not_supported"), unindexed(db, r.snapshotId()));
            assertEquals(1, Config.load().environments.get("mine").supportAccepted, "connecting accepted the list of that moment");
        }
    }

    @Test
    void newlySupportedJarsWaitForTheUsersClick() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        Config config = config("mine", server("server", demo, later));
        try (Db db = Db.open(config.home(), false)) {
            new Environments(config, db).sync("mine", m -> {});
        }
        publish(2, new Object[]{demo, 1, "active"}, new Object[]{later, 2, "active"});
        config = Config.load();
        try (Db db = Db.open(config.home(), false)) {
            // a later automatic sync fetches the new list, but does not index what it newly supports
            Files.setLastModifiedTime(config.home().resolve("catalog/supported.json"), java.nio.file.attribute.FileTime.fromMillis(0));
            Environments.SyncResult r = new Environments(config, db).sync("mine", m -> {});
            assertTrue(r.summary().contains("1 newly supported (sevli accept mine indexes them): later 3.0.0"), r.summary());
            assertFalse(indexed(db, later));
        }
        config.environments.get("mine").supportAccepted = 2; // what `sevli accept mine` does before syncing
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            Environments.SyncResult r = new Environments(config, db).sync("mine", m -> {});
            assertTrue(indexed(db, later), r.summary());
            assertEquals(List.of(), unindexed(db, r.snapshotId()));
        }
    }

    @Test
    void aRetiredVersionKeepsWorkingWhereItWasIndexed() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        Config config = config("old", server("server", demo));
        try (Db db = Db.open(config.home(), false)) {
            new Environments(config, db).sync("old", m -> {});
        }
        publish(2, new Object[]{demo, 1, "retired"});
        Files.setLastModifiedTime(config.home().resolve("catalog/supported.json"), java.nio.file.attribute.FileTime.fromMillis(0));
        Path other = server("second", demo);
        config = config("new", other);
        try (Db db = Db.open(config.home(), false)) {
            Environments envs = new Environments(config, db);
            long kept = envs.sync("old", m -> {}).snapshotId();
            assertEquals(List.of(), unindexed(db, kept), "still indexed where it was accepted");
            long fresh = envs.sync("new", m -> {}).snapshotId();
            assertEquals(List.of("demo:not_supported"), unindexed(db, fresh), "not offered to a newly connected server");
        }
    }

    @Test
    void aListThatDoesNotVerifyIsNeverUsed() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        Files.writeString(catalog.resolve("supported.json"), Files.readString(catalog.resolve("supported.json")).replace("\"active\"", "\"active\" "));
        Config config = config("mine", server("server", demo));
        try (Db db = Db.open(config.home(), false)) {
            Environments.SyncResult r = new Environments(config, db).sync("mine", m -> {});
            assertFalse(indexed(db, demo), r.summary());
            assertTrue(r.summary().contains("0 of 1 jars indexed"), r.summary());
        }
    }

    private static String call(dev.sevli.tools.Tools.Ctx ctx, String tool, String key, String value, String env) {
        com.google.gson.JsonObject a = new com.google.gson.JsonObject();
        if (key != null) a.addProperty(key, value);
        a.addProperty("env", env);
        dev.sevli.tools.Tools.Result r = dev.sevli.tools.Tools.call(ctx, tool, a, Path.of("."));
        assertFalse(r.error(), r.text());
        return r.text();
    }

    @Test
    void jarsIndexedBeforeTheRuleAreHiddenNowAndInHistory() throws Exception {
        Config config = config("mine", server("server", demo, other));
        config.supportPolicy = "all"; // an index built before supported-only indexing (or on the maintainer's install)
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            Environments envs = new Environments(config, db);
            envs.sync("mine", m -> {});
            envs.importFolder("mine", server("backup", other), "0.9", m -> {}); // a past version with only the other mod
        }
        publish(1, new Object[]{demo, 1, "active"});
        config.supportPolicy = null;
        config.environments.get("mine").supportAccepted = 1;
        config.save();
        Supported.fetch(config);
        try (Db db = Db.open(config.home(), true)) {
            var ctx = new dev.sevli.tools.Tools.Ctx(config, db, null);
            String refs = call(ctx, "refs", "target", "LivingEntity.tick", "mine");
            assertTrue(refs.contains("dev.demo.Ticker") && !refs.contains("dev.other"), refs);
            assertTrue(refs.contains("[in indexed jars only: 1 of 2 jars on this server are not indexed (not publicly supported) and may also use or change this: other 2.0.0]"), refs);
            String mixins = call(ctx, "mixins", "target", "LivingEntity", "mine");
            assertTrue(mixins.contains("LivingMixin") && !mixins.contains("TickMixin"), mixins);
            String past = call(ctx, "refs", "target", "LivingEntity.tick", "mine@0.9");
            assertFalse(past.contains("dev.other"), "history is filtered too: " + past);
            String find = call(ctx, "find", "query", "dev.other.Ticker", "mine");
            assertFalse(find.contains("dev.other.Ticker ("), find);
            String env = call(ctx, "env", null, null, "mine");
            assertTrue(env.contains("1 of 2 jars indexed; not indexed (not publicly supported): other 2.0.0"), env);
            assertTrue(indexed(db, other), "hidden, not deleted");
        }
        publish(2, new Object[]{demo, 1, "revoked"});
        Supported.fetch(config);
        try (Db db = Db.open(config.home(), true)) {
            String refs = call(new dev.sevli.tools.Tools.Ctx(config, db, null), "refs", "target", "LivingEntity.tick", "mine");
            assertFalse(refs.contains("dev.demo"), "a revoked jar disappears from answers: " + refs);
        }
    }

    @Test
    void aConfigChangeMakesASnapshotWithoutJarWork() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        Path server = server("server", demo);
        Files.createDirectories(server.resolve("config"));
        Files.writeString(server.resolve("config/demo.json"), "{\"radius\": 8}");
        Config config = config("mine", server);
        try (Db db = Db.open(config.home(), false)) {
            Environments envs = new Environments(config, db);
            long first = envs.sync("mine", m -> {}).snapshotId();
            int artifacts = db.queryInt("SELECT count(*) FROM artifact");
            Files.writeString(server.resolve("config/demo.json"), "{\"radius\": 16}");
            List<String> progress = new java.util.ArrayList<>();
            long second = envs.sync("mine", progress::add).snapshotId();
            assertTrue(second > first, "a changed config is a new point in history");
            assertEquals(artifacts, db.queryInt("SELECT count(*) FROM artifact"), "no jar was indexed again");
            assertTrue(progress.contains("1 jars, 0 new or changed to fetch"), progress.toString());
        }
    }

    @Test
    void fullCoverageAddsNoNote() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        Config config = config("mine", server("server", demo));
        try (Db db = Db.open(config.home(), false)) {
            new Environments(config, db).sync("mine", m -> {});
            String refs = call(new dev.sevli.tools.Tools.Ctx(config, db, null), "refs", "target", "LivingEntity.tick", "mine");
            assertFalse(refs.contains("not indexed"), refs);
        }
    }

    @Test
    void reviewShowsWhatTheMaintainerRunsThatIsNotPublishedYet() throws Exception {
        publish(1, new Object[]{demo, 1, "active"});
        byte[] demoNext = Fixture.mod("demo", "1.1.0", "dev/demo/mixin/LivingMixin", "demo$onTick");
        Config config = config("mine", server("server", demoNext, other));
        config.supportPolicy = "all";
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            new Environments(config, db).sync("mine", m -> {});
            var items = dev.sevli.catalog.Review.items(config, db, Supported.fetch(config));
            assertEquals(List.of("demo 1.1.0 new_version (published 1.0.0)", "other 2.0.0 new_mod (published null)"),
                    items.stream().map(i -> i.modId() + " " + i.version() + " " + i.kind() + " (published " + i.publishedVersions() + ")").sorted().toList());
        }
    }

    @Test
    void keysMadeBySevliSignListsItAccepts() throws Exception {
        String[] pair = Supported.newKeyPair();
        byte[] data = "{\"schema\":1,\"catalogVersion\":1,\"jars\":{}}".getBytes(StandardCharsets.UTF_8);
        System.setProperty("sevli.catalogKey", pair[0]);
        Files.write(catalog.resolve("supported.json"), data);
        Files.writeString(catalog.resolve("supported.json.sig"), Supported.sign(data, pair[1]));
        Config config = config("mine", server("server", demo));
        assertEquals(1, Supported.fetch(config).catalogVersion);
        System.setProperty("sevli.catalogKey", Supported.newKeyPair()[0]); // someone else's key
        assertThrows(java.io.IOException.class, () -> Supported.fetch(config));
    }

    @Test
    void theMaintainersInstallIndexesEverything() throws Exception {
        Config config = config("mine", server("server", demo, other));
        config.supportPolicy = "all";
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            Environments.SyncResult r = new Environments(config, db).sync("mine", m -> {});
            assertTrue(indexed(db, demo) && indexed(db, other), r.summary());
            assertEquals(List.of(), unindexed(db, r.snapshotId()));
        }
    }
}
