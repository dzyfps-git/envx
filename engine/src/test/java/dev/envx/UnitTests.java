package dev.envx;

import dev.envx.env.LoaderLog;
import dev.envx.env.SecretFilter;
import dev.envx.query.Sig;
import dev.envx.query.Target;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests; no index or network needed. */
class UnitTests {

    @Test
    void targetParsesYarnAndIntermediaryAndFrames() {
        assertEquals(new Target("LivingEntity", "tick", null), Target.parse("LivingEntity.tick"));
        assertEquals(new Target("net.minecraft.class_1309", "method_5773", null), Target.parse("net.minecraft.class_1309.method_5773"));
        assertEquals(new Target("class_3218", "method_18765", "(Lnet/minecraft/class_1297;)V"), Target.parse("class_3218.method_18765(Lnet/minecraft/class_1297;)V"));
        assertEquals(new Target("net.minecraft.server.world.ServerWorld", "tick", null),
                Target.parse("at net.minecraft.server.world.ServerWorld.tick(ServerWorld.java:123) ~[server.jar:?]"));
        assertEquals(new Target("net.minecraft.entity.LivingEntity", null, null), Target.parse("net.minecraft.entity.LivingEntity"));
        // frames pasted straight from a log line keep their class-loader or module prefix
        assertEquals(new Target("net.minecraft.class_1309", "method_5773", null),
                Target.parse("at knot//net.minecraft.class_1309.method_5773(class_1309.java:1234) ~[server-intermediary.jar:?]"));
        assertEquals(new Target("java.lang.Thread", "run", null), Target.parse("\tat java.base/java.lang.Thread.run(Thread.java:1583)"));
        assertEquals(new Target("net/minecraft/class_1309", null, null), Target.parse("net/minecraft/class_1309")); // internal names untouched
        assertEquals(new Target("LivingEntity", "tick", null), Target.parse("LivingEntity#tick"));
        assertEquals(new Target(null, "method_5773", null), Target.parse("method_5773"));
        assertEquals(new Target("MobEntity", "<init>", null), Target.parse("MobEntity.<init>"));
    }

    @Test
    void modQualifierIsExtractedFromAnyQuery() {
        var p = dev.envx.query.ModFilter.extract("*claim*tick* mod:openpartiesandclaims");
        assertEquals("*claim*tick*", p.rest());
        assertEquals(List.of("openpartiesandclaims"), p.terms());
        var multi = dev.envx.query.ModFilter.extract("mod:Lithium,spark ServerWorld.tick");
        assertEquals("ServerWorld.tick", multi.rest());
        assertEquals(List.of("lithium", "spark"), multi.terms());
        assertTrue(dev.envx.query.ModFilter.extract("mod:lithium").onlyMods());
        var none = dev.envx.query.ModFilter.extract("LivingEntity.tick");
        assertEquals("LivingEntity.tick", none.rest());
        assertTrue(none.terms().isEmpty());
        // a mod: inside a word is not a qualifier
        assertTrue(dev.envx.query.ModFilter.extract("foomod:bar").terms().isEmpty());
    }

    @Test
    void packIdentityComesFromBcc() {
        var p = dev.envx.env.PackInfo.fromBcc("""
                {"projectID":466901,"modpackName":"Prominence II: Hasturian Era","modpackVersion":"v4.1.0","useMetadata":false}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("curseforge:466901", p.pack());
        assertEquals("4.1.0", p.version());
        assertEquals("Prominence II: Hasturian Era", p.name());
        var noId = dev.envx.env.PackInfo.fromBcc("{\"modpackName\":\"My Pack!\",\"modpackVersion\":\"1.2\"}".getBytes());
        assertEquals("my-pack", noId.pack());
        assertNull(dev.envx.env.PackInfo.fromBcc("not json".getBytes()));
        assertNull(dev.envx.env.PackInfo.fromBcc(null));
    }

    @Test
    void syncLockIsExclusiveAndReleasable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        assertTrue(dev.envx.env.AutoSync.acquire(home, "e"));
        assertFalse(dev.envx.env.AutoSync.acquire(home, "e"));
        dev.envx.env.AutoSync.release(home, "e");
        assertTrue(dev.envx.env.AutoSync.acquire(home, "e"));
    }

    @Test
    void cappedOutputListsDroppedLinesByName() {
        var out = new dev.envx.query.Out(200);
        out.force("header");
        for (int i = 0; i < 20; i++) out.line("  m method" + i + "() -> void  [method_" + i + "]", "Owner", "method" + i);
        String s = out.finish("narrow it");
        assertTrue(s.contains("more line(s) omitted: Owner(method"), s);
        assertTrue(s.contains("method19") || s.contains(" more"), s);
        assertTrue(s.endsWith("narrow it\n"), s);
    }

    @Test
    void severalMembersParseAsOneMemberList() {
        assertEquals(new Target("ServerChunkManager", "tick,tickChunks", null), Target.parse("ServerChunkManager.tick,tickChunks"));
    }

    @Test
    void loaderLogParsesTreeOutput() {
        String log = """
                [11:50:53] [main/INFO]: Loading 5 mods:
                \t- accessories 1.0.0-beta.48+1.20.1
                \t   |-- io_wispforest_endec 0.1.8
                \t   \\-- io_wispforest_endec_netty 0.1.4
                \t- fabric-api 0.92.2+1.20.1
                \t   |   |-- deep 1.0
                [11:50:53] [main/WARN]: something else
                """;
        List<LoaderLog.LoadedMod> mods = LoaderLog.parse(log);
        assertEquals(5, mods.size());
        assertEquals(new LoaderLog.LoadedMod("accessories", "1.0.0-beta.48+1.20.1", false), mods.get(0));
        assertEquals(new LoaderLog.LoadedMod("io_wispforest_endec_netty", "0.1.4", true), mods.get(2));
        assertEquals(new LoaderLog.LoadedMod("deep", "1.0", true), mods.get(4));
    }

    @Test
    void secretsAreRedacted() {
        String props = "rcon.password=hunter2\nenable-rcon=true\nlevel-name=world\n";
        String r = SecretFilter.redact(props);
        assertFalse(r.contains("hunter2"));
        assertTrue(r.contains("enable-rcon=true"));
        assertTrue(r.contains("level-name=world"));
        String json = "{\"botToken\": \"abc.def\", \"require_password\": false, \"webhook\": \"https://discord.com/api/webhooks/1/x\"}";
        String j = SecretFilter.redact(json);
        assertFalse(j.contains("abc.def"));
        assertFalse(j.contains("api/webhooks/1"));
        assertTrue(j.contains("\"require_password\": false"));
        assertTrue(SecretFilter.isSecretFile("config/.env"));
        assertTrue(SecretFilter.isSecretFile("ops.json"));
        assertFalse(SecretFilter.isSecretFile("config/lithium.properties"));
    }

    @Test
    void signaturesRenderReadably() {
        assertEquals("(Entity, float) -> void", Sig.method("(Lnet/minecraft/entity/Entity;F)V"));
        assertEquals("int[][]", Sig.field("[[I"));
        assertEquals(2, Sig.paramCount("(IJ)V"));
        assertNull(null);
    }
}
