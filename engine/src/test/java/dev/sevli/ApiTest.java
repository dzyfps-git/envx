package dev.sevli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.api.Api;
import dev.sevli.env.Environments;
import dev.sevli.store.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code sevli api} (docs/api.md) on a made-up environment: every operation, the error envelope, weak spot 13. */
class ApiTest {
    @TempDir
    static Path tmp;
    private static String previousHome;
    private static Config config;
    private static Db db;
    private static long current, past;

    @BeforeAll
    static void build() throws Exception {
        previousHome = System.getProperty("sevli.home");
        Path home = tmp.resolve("home");
        System.setProperty("sevli.home", home.toString());
        Fixture.base(home.resolve("base").resolve("minecraft-1.20.1-yarn-1.20.1+build.10"));

        Path server = tmp.resolve("server");
        Files.createDirectories(server.resolve("mods"));
        Files.write(server.resolve("mods/demo-1.0.0.jar"), Fixture.mod("demo", "1.0.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));
        Files.write(server.resolve("mods/gfx-1.0.0.jar"), gfx());
        Files.createDirectories(server.resolve("logs"));
        // A dedicated server's loader prints nested client-only jars in its tree but does not load them.
        Files.writeString(server.resolve("logs/latest.log"), """
                [12:00:00] [main/INFO]: Loading 3 mods:
                \t- demo 1.0.0
                \t- gfx 1.0.0
                \t   \\-- gfxlib 3.0.0
                \t- minecraft 1.20.1
                [12:00:01] [main/INFO]: done
                """);

        config = Config.load();
        config.supportPolicy = "all"; // the whole pipeline; supported-only indexing is tested in SupportGateTest
        Config.EnvDef def = new Config.EnvDef();
        def.sources.add(server.toString());
        config.environments.put("fx", def);
        config.autoSyncHours = 0;
        config.save();
        db = Db.open(config.home(), false);
        Path old = tmp.resolve("backup-0.9");
        Files.createDirectories(old.resolve("mods"));
        Files.write(old.resolve("mods/demo-0.9.0.jar"), Fixture.mod("demo", "0.9.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));
        new Environments(config, db).importFolder("fx", old, "0.9", m -> {});
        new Environments(config, db).sync("fx", m -> {});
        past = db.queryLong("SELECT id FROM snapshot WHERE kind='import'");
        current = db.queryLong("SELECT id FROM snapshot WHERE kind='sync'");
    }

    @AfterAll
    static void close() throws Exception {
        if (db != null) db.close();
        if (previousHome == null) System.clearProperty("sevli.home");
        else System.setProperty("sevli.home", previousHome);
    }

    /** gfx 1.0.0, bundling gfxlib 3.0.0, which declares {@code "environment": "client"}. */
    private static byte[] gfx() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"gfxlib\",\"version\":\"3.0.0\",\"environment\":\"client\"}".getBytes(StandardCharsets.UTF_8));
        lib.put("dev/gfxlib/Render.class", Fixture.mcClass("dev/gfxlib/Render", "java/lang/Object", "draw"));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"gfx\",\"version\":\"1.0.0\",\"jars\":[{\"file\":\"META-INF/jars/gfxlib-3.0.0.jar\"}]}".getBytes(StandardCharsets.UTF_8));
        outer.put("dev/gfx/Main.class", Fixture.mcClass("dev/gfx/Main", "java/lang/Object", "run"));
        outer.put("META-INF/jars/gfxlib-3.0.0.jar", Fixture.jar(lib));
        return Fixture.jar(outer);
    }

    private static List<JsonObject> api(String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Api.run(config, new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonObject> res = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) res.add(JsonParser.parseString(l).getAsJsonObject());
        return res;
    }

    private static JsonObject ok(JsonObject r) {
        assertEquals(1, r.get("api").getAsInt(), r.toString());
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        return r.getAsJsonObject("result");
    }

    @Test
    void snapshotsListBothWithTheirKeys() throws Exception {
        JsonArray snaps = ok(api("{\"id\":1,\"op\":\"snapshots\",\"env\":\"fx\"}").getFirst()).getAsJsonArray("snapshots");
        assertEquals(2, snaps.size());
        JsonObject cur = snaps.get(0).getAsJsonObject();
        assertEquals(current, cur.get("id").getAsLong());
        assertTrue(cur.get("current").getAsBoolean());
        assertEquals("sync", cur.get("kind").getAsString());
        assertEquals(64, cur.get("fingerprint").getAsString().length());
        assertFalse(cur.get("modset").isJsonNull()); // a loader list
        assertTrue(snaps.get(1).getAsJsonObject().get("modset").isJsonNull()); // the backup has no log
    }

    @Test
    void matchUsesWhatTheLoaderLoaded() throws Exception { // weak spot 13: the nested client-only gfxlib does not count
        String loaded = "[[\"demo\",\"1.0.0\"],[\"gfx\",\"1.0.0\"],[\"minecraft\",\"1.20.1\"]]";
        JsonObject m = ok(api("{\"id\":2,\"op\":\"match\",\"env\":\"fx\",\"mods\":" + loaded + "}").getFirst());
        assertEquals("match", m.get("status").getAsString(), m.toString());
        assertEquals(current, m.getAsJsonArray("snapshots").get(0).getAsLong());
        JsonObject snap = ok(api("{\"op\":\"snapshots\",\"env\":\"fx\"}").getFirst()).getAsJsonArray("snapshots").get(0).getAsJsonObject();
        assertEquals(snap.get("modset"), m.get("modset"));

        JsonObject none = ok(api("{\"op\":\"match\",\"env\":\"fx\",\"mods\":[[\"demo\",\"1.0.0\"],[\"gfx\",\"1.0.0\"],[\"gfxlib\",\"3.0.0\"],[\"minecraft\",\"1.20.1\"]]}").getFirst());
        assertEquals("none", none.get("status").getAsString());
        JsonObject closest = none.getAsJsonObject("closest");
        assertEquals(current, closest.get("snapshot").getAsLong());
        assertEquals("gfxlib", closest.getAsJsonArray("only_in_request").get(0).getAsJsonArray().get(0).getAsString());
        assertEquals(0, closest.get("only_in_snapshot_count").getAsInt());
    }

    @Test
    void ownerResolvesVanillaLambdasAndMergedMixinHandlers() throws Exception {
        JsonObject r = ok(api("{\"id\":3,\"op\":\"owner\",\"env\":\"fx\",\"keys\":["
                + "{\"class\":\"net.minecraft.class_1309\",\"method\":\"method_5773\",\"desc\":\"()V\"},"
                + "{\"class\":\"net.minecraft.class_1309$$Lambda$12/0x0000000800c1\",\"method\":\"method_5773\"},"
                + "{\"class\":\"net/minecraft/class_1309\",\"method\":\"handler$zza000$demo$demo$onTick\"},"
                + "{\"class\":\"dev.gfxlib.Render\",\"method\":\"draw\"},"
                + "{\"class\":\"net.minecraft.class_9999\"}]}").getFirst());
        assertEquals(64, r.getAsJsonObject("snapshot").get("fingerprint").getAsString().length());
        JsonArray res = r.getAsJsonArray("results");
        assertEquals(5, res.size());

        JsonObject tick = res.get(0).getAsJsonObject();
        assertEquals("probable", tick.get("status").getAsString());
        JsonObject mc = tick.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("minecraft", mc.get("mod").getAsString());
        assertEquals("1.20.1", mc.get("version").getAsString());
        assertTrue(mc.get("loaded").getAsBoolean());
        assertEquals("net.minecraft.entity.LivingEntity", tick.getAsJsonObject("yarn").get("class").getAsString());
        assertEquals("tick", tick.getAsJsonObject("yarn").get("method").getAsString());
        assertTrue(tick.get("member_found").getAsBoolean());
        assertTrue(tick.get("mixin").isJsonNull());

        JsonObject lambda = res.get(1).getAsJsonObject();
        assertTrue(lambda.get("hidden_lambda").getAsBoolean());
        assertTrue(lambda.get("class_found").getAsBoolean());

        JsonObject merged = res.get(2).getAsJsonObject(); // the hash between the $ signs is ignored
        assertEquals("probable", merged.get("status").getAsString(), merged.toString());
        assertEquals("demo", merged.getAsJsonArray("candidates").get(0).getAsJsonObject().get("mod").getAsString());
        JsonObject mixin = merged.getAsJsonObject("mixin");
        assertEquals("demo", mixin.get("mod").getAsString());
        assertEquals("dev.demo.mixin.LivingMixin", mixin.get("mixin_class").getAsString());
        assertEquals("Inject", mixin.get("kind").getAsString());
        assertEquals("demo$onTick", mixin.get("handler").getAsString());

        JsonObject client = res.get(3).getAsJsonObject(); // weak spot 13: indexed, but not loaded on a server
        assertEquals("none", client.get("status").getAsString(), client.toString());
        JsonObject lib = client.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("gfxlib", lib.get("mod").getAsString());
        assertFalse(lib.get("loaded").getAsBoolean());
        assertEquals("gfx", lib.getAsJsonArray("nested_in").get(0).getAsJsonObject().get("mod").getAsString());

        JsonObject unknown = res.get(4).getAsJsonObject();
        assertEquals("none", unknown.get("status").getAsString());
        assertFalse(unknown.get("class_found").getAsBoolean());
        assertTrue(unknown.get("member_found").isJsonNull());
    }

    @Test
    void mixinsListDeclaredInjectionsWithLogEvidence() throws Exception {
        JsonObject r = ok(api("{\"op\":\"mixins\",\"env\":\"fx\",\"targets\":[{\"class\":\"net.minecraft.class_1309\",\"method\":\"method_5773\",\"desc\":\"()V\"},{\"class\":\"net.minecraft.class_1297\"}]}").getFirst());
        JsonArray res = r.getAsJsonArray("results");
        JsonObject m = res.get(0).getAsJsonObject().getAsJsonArray("mixins").get(0).getAsJsonObject();
        assertEquals("demo", m.get("mod").getAsString());
        assertEquals("1.0.0", m.get("version").getAsString());
        assertEquals("Inject", m.get("kind").getAsString());
        assertEquals("HEAD", m.get("at").getAsString());
        assertFalse(m.get("failed").getAsBoolean()); // current snapshot: the log has no failure
        assertEquals(0, res.get(1).getAsJsonObject().getAsJsonArray("mixins").size());
        JsonObject old = ok(api("{\"op\":\"mixins\",\"snapshot\":" + past + ",\"targets\":[{\"class\":\"net.minecraft.class_1309\"}]}").getFirst());
        JsonObject pm = old.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonArray("mixins").get(0).getAsJsonObject();
        assertEquals("0.9.0", pm.get("version").getAsString());
        assertTrue(pm.get("failed").isJsonNull()); // no log evidence for past snapshots
    }

    @Test
    void diffNamesChangedAndAddedJars() throws Exception {
        JsonObject d = ok(api("{\"op\":\"diff\",\"from\":" + past + ",\"to\":" + current + "}").getFirst());
        JsonObject changed = d.getAsJsonArray("changed").get(0).getAsJsonObject();
        assertEquals("demo", changed.get("mod").getAsString());
        assertEquals("0.9.0", changed.get("from").getAsString());
        assertEquals("1.0.0", changed.get("to").getAsString());
        assertTrue(d.getAsJsonArray("added").toString().contains("\"gfx\""));
        assertFalse(d.getAsJsonArray("added").toString().contains("gfxlib")); // not loaded on a server
        assertEquals(0, d.getAsJsonArray("removed").size());
    }

    @Test
    void errorsKeepTheBatchGoingAndEchoIds() throws Exception {
        StringBuilder many = new StringBuilder("{\"id\":\"big\",\"op\":\"owner\",\"env\":\"fx\",\"keys\":[");
        for (int i = 0; i <= 5000; i++) many.append(i == 0 ? "" : ",").append("{\"class\":\"a\"}");
        List<JsonObject> r = api("{\"id\":\"x\",\"op\":\"nope\"}", "not json", "{\"id\":7,\"op\":\"owner\",\"snapshot\":99999,\"keys\":[]}",
                "{\"id\":8,\"op\":\"snapshots\",\"env\":\"missing\"}", many + "]}", "{\"id\":9,\"op\":\"snapshots\",\"env\":\"fx\"}");
        assertEquals(6, r.size());
        String[] codes = {"unknown_op", "bad_request", "unknown_snapshot", "unknown_env", "too_many"};
        for (int i = 0; i < codes.length; i++) {
            assertFalse(r.get(i).get("ok").getAsBoolean());
            assertEquals(codes[i], r.get(i).getAsJsonObject("error").get("code").getAsString(), r.get(i).toString());
        }
        assertEquals("x", r.get(0).get("id").getAsString());
        assertTrue(r.get(1).get("id").isJsonNull());
        JsonElement last = r.get(5).get("ok");
        assertTrue(last.getAsBoolean());
    }

    @Test
    void theServerNeverCountsClientOnlyJarsAsLoaded() throws Exception { // weak spot 13, at sync time
        assertEquals(0, db.queryInt("SELECT sa.loaded FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id "
                + "WHERE sa.snapshot_id=? AND a.mod_id='gfxlib'", current));
        assertEquals(1, db.queryInt("SELECT sa.loaded FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id "
                + "WHERE sa.snapshot_id=? AND a.mod_id='gfx'", current));
    }
}
