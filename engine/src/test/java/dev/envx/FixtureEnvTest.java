package dev.envx;

import com.google.gson.JsonObject;
import dev.envx.env.Environments;
import dev.envx.store.Db;
import dev.envx.tools.Tools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline on a tiny made-up environment: a two-class "Minecraft" base, a server folder with two mods that
 * inject into the same method, its log, and a mod project. Runs anywhere (CI included): no network, no real index.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FixtureEnvTest {
    @TempDir
    static Path tmp;
    private static String previousHome;
    private static Config config;
    private static Db db;
    private static Tools.Ctx ctx;
    private static Path server;
    private static Path project;

    @BeforeAll
    static void build() throws Exception {
        previousHome = System.getProperty("envx.home");
        Path home = tmp.resolve("home");
        System.setProperty("envx.home", home.toString());
        Fixture.base(home.resolve("base").resolve("minecraft-1.20.1-yarn-1.20.1+build.10"));

        server = tmp.resolve("server");
        Files.createDirectories(server.resolve("mods"));
        Files.write(server.resolve("mods/demo-1.0.0.jar"), Fixture.mod("demo", "1.0.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));
        Files.write(server.resolve("mods/other-2.0.0.jar"), Fixture.mod("other", "2.0.0", "dev/other/mixin/TickMixin", "other$tick"));
        Files.createDirectories(server.resolve("config"));
        Files.writeString(server.resolve("config/demo.json"), """
                {
                  "radius": 8,
                  // required advancements
                  "advancements": {
                    "examplemod:exampleadvancement": false,
                    "prominent:1000_years_later": true
                  }
                }
                """);
        Files.createDirectories(server.resolve("logs"));
        Files.writeString(server.resolve("logs/latest.log"), """
                [12:00:00] [main/INFO]: Loading 3 mods:
                \t- demo 1.0.0
                \t- minecraft 1.20.1
                \t- other 2.0.0
                [12:00:05] [Server thread/ERROR]: Ticking failed for a demo entity
                java.lang.IllegalStateException: fixture failure
                \tat knot//dev.demo.mixin.LivingMixin.demo$onTick(LivingMixin.java:12) ~[demo-1.0.0.jar:?]
                """);

        project = tmp.resolve("demo-project");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.createDirectories(project.resolve("build/libs"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'fabric-loom' version '1.10.5' }\n");
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"),
                "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1.1.0\",\"mixins\":[\"demo.mixins.json\"],\"depends\":{\"other\":\">=2.0\"}}");
        Files.write(project.resolve("build/libs/demo-1.1.0.jar"), Fixture.mod("demo", "1.1.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));

        config = Config.load();
        Config.EnvDef def = new Config.EnvDef();
        def.sources.add(server.toString());
        config.environments.put("fx", def);
        config.autoSyncHours = 0;
        config.save(); // child processes (project: indexing) read it
        db = Db.open(config.home(), false);
        new Environments(config, db).sync("fx", m -> {});

        Path old = tmp.resolve("backup-0.9"); // a past pack version, imported as history
        Files.createDirectories(old.resolve("mods"));
        Files.write(old.resolve("mods/demo-0.9.0.jar"), Fixture.mod("demo", "0.9.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));
        Files.write(old.resolve("mods/other-2.0.0.jar"), Fixture.mod("other", "2.0.0", "dev/other/mixin/TickMixin", "other$tick"));
        new Environments(config, db).importFolder("fx", old, "0.9", m -> {});
        ctx = new Tools.Ctx(config, db, null);
    }

    @AfterAll
    static void close() throws Exception {
        if (db != null) db.close();
        if (previousHome == null) System.clearProperty("envx.home");
        else System.setProperty("envx.home", previousHome);
    }

    private static String call(String tool, String key, String value, Path cwd) {
        JsonObject a = new JsonObject();
        if (key != null) a.addProperty(key, value);
        a.addProperty("env", "fx");
        Tools.Result r = Tools.call(ctx, tool, a, cwd);
        assertFalse(r.error(), r.text());
        return r.text();
    }

    @Test
    void grepFindsFilesByNameAndTakesPathsAsShown() { // study: an advancement's id is its file name, not its text
        String out = call("grep", "pattern", "camp", tmp);
        assertTrue(out.startsWith("No text matches for /camp/"), out);
        assertTrue(out.contains("demo:data/demo/worldgen/structure/camp.json"), out);
        JsonObject a = new JsonObject();
        a.addProperty("pattern", "spawn_overrides");
        a.addProperty("scope", "resources");
        a.addProperty("path", "demo:data/demo/worldgen"); // copied from an answer, label included
        a.addProperty("env", "fx");
        Tools.Result r = Tools.call(ctx, "grep", a, tmp);
        assertTrue(r.text().contains("1 match(es) in 1 file(s)"), r.text());
        assertFalse(call("grep", "pattern", ".", tmp).contains("whose path matches"), "a pattern matching anything names no files");
    }

    @Test
    void fieldUsesIncludeTheClassItselfAndSayReadOrWrite() { // study: agents grepped source for "who reads this field"
        String out = call("refs", "target", "LivingEntity.persistent", tmp);
        assertTrue(out.startsWith("uses of LivingEntity.persistent"), out);
        assertTrue(out.contains("LivingEntity.isPersistent() -> boolean") || out.contains("LivingEntity.isPersistent()"), out);
        assertTrue(out.contains("(read)"), out);
        assertTrue(out.contains("1 read(s), 0 write(s)"), out);
    }

    @Test
    void namesResolveBothWays() {
        assertTrue(call("find", "query", "LivingEntity.tick", tmp).contains("class_1309.method_5773"));
        assertTrue(call("find", "query", "net.minecraft.class_1309.method_5773", tmp).contains("LivingEntity.tick"));
    }

    @Test
    void mixinsFromBothModsAreListed() {
        String out = call("mixins", "target", "LivingEntity.tick", tmp);
        assertTrue(out.contains("demo 1.0.0") && out.contains("other 2.0.0"), out);
    }

    @Test
    void outlineListsMembersWithBothNames() {
        String out = call("outline", "target", "LivingEntity", tmp);
        assertTrue(out.contains("tick") && out.contains("method_5773"), out);
    }

    @Test
    void filteredOutlineShowsFieldsTheMatchingMethodsUse() { // a name filter alone missed MobEntity.persistent
        JsonObject a = new JsonObject();
        a.addProperty("target", "LivingEntity");
        a.addProperty("filter", "despawn");
        a.addProperty("env", "fx");
        String out = Tools.call(ctx, "outline", a, tmp).text();
        assertTrue(out.contains("checkDespawn()"), out);
        assertTrue(out.contains("persistent: boolean  [field_90001]  (via isPersistent())"), out);
    }

    @Test
    void refsFindCallersInMods() {
        String out = call("refs", "target", "LivingEntity.tick", tmp);
        assertTrue(out.contains("Ticker"), out);
    }

    @Test
    void modQualifierNarrowsResults() {
        String out = call("mixins", "target", "LivingEntity.tick mod:other", tmp);
        assertTrue(out.contains("other 2.0.0") && !out.contains("demo 1.0.0"), out);
    }

    @Test
    void projectQualifierPutsTheBuildInPlaceOfTheDeployedJar() {
        String out = call("mixins", "target", "LivingEntity.tick project:", project);
        assertTrue(out.startsWith("[with project build demo 1.1.0"), out);
        assertTrue(out.contains("demo 1.1.0  <-") && !out.contains("demo 1.0.0  <-"), out); // replaced, not listed twice
    }

    @Test
    void pastVersionsAreQueriedOnlyWhenAskedAndAreMarked() {
        JsonObject a = new JsonObject();
        a.addProperty("filter", "demo");
        a.addProperty("env", "fx@0.9");
        String past = Tools.call(ctx, "env", a, tmp).text();
        assertTrue(past.startsWith("[fx@0.9: past snapshot") && past.contains("0.9.0"), past);
        assertTrue(call("env", "filter", "demo", tmp).contains("1.0.0"));
    }

    @Test
    void rawLogLinesAreSearchable() {
        JsonObject a = new JsonObject();
        a.addProperty("pattern", "fixture failure");
        a.addProperty("scope", "logs");
        a.addProperty("env", "fx");
        assertTrue(Tools.call(ctx, "grep", a, tmp).text().contains("fixture failure"));
    }

    @Test
    void checkMixinsValidatesTheProjectAndNamesTheOtherMod() {
        String out = call("check_mixins", "project", project.toString(), project);
        assertTrue(out.contains("0 error(s)"), out);
        assertTrue(out.contains("other 2.0.0"), out);
    }

    @Test
    void envSummaryComparesTheProjectWithTheServer() {
        String out = call("env", null, null, project);
        assertTrue(out.contains("project demo 1.1.0"), out);
        assertTrue(out.contains("on server: demo 1.0.0"), out);
        assertTrue(out.contains("other >=2.0 ok"), out);
    }

    @Test
    void serverLogErrorsAreGroupedWithTheirMod() {
        String out = call("env", "filter", "errors", tmp);
        assertTrue(out.contains("Ticking failed for a demo entity"), out);
        assertTrue(out.contains("demo"), out);
    }

    @Test
    void serverLogErrorsCanBeAskedForByDate() { // Q17 weak spot: errors:<date> matched message text and found nothing
        String day = java.time.LocalDate.now().toString(); // latest.log is dated by its modification time
        String out = call("env", "filter", "errors:" + day, tmp);
        assertTrue(out.contains("on " + day) && out.contains("Ticking failed for a demo entity"), out);
        out = call("env", "filter", "errors:1999-01-01", tmp);
        assertTrue(out.contains("nothing matches on 1999-01-01; without the filter:"), out);
    }

    @Test
    void modCardNamesTheServerFilesForTheMod() { // Q16 weak spot: server values were never read
        String out = call("env", "filter", "demo", tmp);
        assertTrue(out.contains("server files naming it: config/demo.json"), out);
    }

    @Test
    void searchInOneModAlsoFindsMatchingCallsAndData() { // Q16 weak spot: names alone missed addSpawn and spawn_overrides
        String out = call("find", "query", "tick mod:demo", tmp);
        assertTrue(out.contains("LivingEntity.tick  <- Ticker.tickIt"), out);
        out = call("find", "query", "spawn mod:demo", tmp);
        assertTrue(out.contains("data files mentioning 'spawn' (1; grep spawn scope=resources mod:demo):"), out);
        assertTrue(out.contains("  worldgen/structure x1, e.g. camp.json: {\"spawn_overrides\":{\"monster\":{}}}"), out);
    }

    @Test
    void aConfigMatchShowsItsWholeJsonBlock() { // Q16 weak spot: one entry of a two-entry map was read as the whole map
        JsonObject a = new JsonObject();
        a.addProperty("pattern", "examplemod");
        a.addProperty("scope", "config");
        a.addProperty("env", "fx");
        String out = Tools.call(ctx, "grep", a, tmp).text();
        assertTrue(out.startsWith("1 match(es) in 1 file(s)"), out);
        assertTrue(out.contains("5: \"examplemod:exampleadvancement\": false,"), out);
        assertTrue(out.contains("6- \"prominent:1000_years_later\": true") && out.contains("4- \"advancements\": {"), out);
        assertFalse(out.contains("2- "), out); // not the whole file
    }

    @Test
    void sourceIsDecompiledOnDemand() {
        String out = call("source", "target", "LivingEntity.tick", tmp);
        assertTrue(out.contains("tick("), out);
    }

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void everyLoadedJarIsDecompiledIntoTheSourceSearch() throws Exception { // study: grep scope=source found more with all code
        JsonObject a = new JsonObject();
        a.addProperty("pattern", "onTick|tick");
        a.addProperty("scope", "source");
        a.addProperty("env", "fx");
        assertTrue(Tools.call(ctx, "grep", a, tmp).text().contains("jars fully decompiled, the rest only where read before"));

        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        dev.envx.query.FullDecompile.decompilePending(config, "fx", new java.io.PrintStream(log, true));
        assertTrue(log.toString().contains("done: 3 of 3 jar(s)"), log.toString());
        String out = Tools.call(ctx, "grep", a, tmp).text();
        assertTrue(out.contains("== minecraft:net/minecraft/entity/LivingEntity.java"), out);
        assertTrue(out.contains("== demo:dev/demo/mixin/LivingMixin.java"), out); // labeled by mod, and only the loaded version
        assertFalse(out.contains("fully decompiled"), out);
        assertTrue(dev.envx.query.FullDecompile.status(config, "fx").contains("3 of 3 jars fully decompiled"));
        log.reset();
        dev.envx.query.FullDecompile.decompilePending(config, "fx", new java.io.PrintStream(log, true));
        assertTrue(log.toString().contains("0 jar(s) to decompile"), log.toString()); // each jar once

        // folders decompiled before packs existed (1.3.0/1.3.1) are packed, not decompiled again
        Path pack;
        try (var dirs = Files.list(config.home().resolve("decomp"))) {
            pack = dirs.filter(d -> d.getFileName().toString().startsWith("fabric-")).findFirst().orElseThrow().resolve(".pack");
        }
        Files.delete(pack);
        assertTrue(Tools.call(ctx, "grep", a, tmp).text().contains("== minecraft:net/minecraft/entity/LivingEntity.java")); // folder walk
        log.reset();
        dev.envx.query.FullDecompile.decompilePending(config, "fx", new java.io.PrintStream(log, true));
        assertTrue(log.toString().contains("1 jar(s) to decompile") && Files.exists(pack), log.toString());
        assertTrue(Files.readString(pack).contains("\u0001net/minecraft/entity/LivingEntity.java\n"));
    }

    @Test
    void configsAreSearchable() {
        JsonObject a = new JsonObject();
        a.addProperty("pattern", "radius");
        a.addProperty("scope", "config");
        a.addProperty("env", "fx");
        assertTrue(Tools.call(ctx, "grep", a, tmp).text().contains("demo.json"));
    }

    @Test
    @Order(Integer.MAX_VALUE) // changes the environment: runs last
    void aPackUpdateIsReportedForTheProject() throws Exception {
        Files.delete(server.resolve("mods/other-2.0.0.jar"));
        Files.write(server.resolve("mods/other-2.1.0.jar"), Fixture.mod("other", "2.1.0", "dev/other/mixin/TickMixin", "other$tickLater"));
        Files.writeString(server.resolve("config/demo.json"), "{\n  \"radius\": 12\n}\n");
        String log = Files.readString(server.resolve("logs/latest.log")).replace("other 2.0.0", "other 2.1.0");
        Files.writeString(server.resolve("logs/latest.log"), log);
        new Environments(config, db).sync("fx", m -> {});

        String out = call("env", "filter", "diff:1..", project);
        assertTrue(out.contains("other 2.0.0 -> 2.1.0"), out);
        assertTrue(out.contains("## Project demo 1.1.0"), out);
        assertTrue(out.contains("1 new, 1 gone"), out); // the rival's handler changed
        assertTrue(out.contains("other 2.0.0 -> 2.1.0 (depends, >=2.0 ok)"), out);
        assertTrue(out.contains("demo.json (this project's config)") && out.contains("+ \"radius\": 12"), out);
    }
}
