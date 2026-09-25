package dev.envx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.envx.env.Environments;
import dev.envx.store.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A controlled pack update, end to end, the way it happens in use: the pack on the "server" changes, an agent session
 * (the real MCP server, as its own process) makes a call, use-time auto-sync starts a background sync process, and
 * the update becomes a new labeled snapshot. The running session keeps answering from the snapshot it started with;
 * a new session sees the update and what it means for the project. A second sync with no change adds nothing.
 */
class AutoSyncEndToEndTest {
    @TempDir
    static Path tmp;
    private static Path home, server, project;
    private static String previousHome;

    @BeforeAll
    static void build() throws Exception {
        previousHome = System.getProperty("envx.home");
        home = tmp.resolve("home");
        System.setProperty("envx.home", home.toString());
        Fixture.base(home.resolve("base").resolve("minecraft-1.20.1-yarn-1.20.1+build.10"));

        server = tmp.resolve("server");
        Files.createDirectories(server.resolve("mods"));
        Files.createDirectories(server.resolve("config"));
        Files.createDirectories(server.resolve("logs"));
        Files.write(server.resolve("mods/demo-1.0.0.jar"), Fixture.mod("demo", "1.0.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));
        Files.write(server.resolve("mods/other-2.0.0.jar"), Fixture.mod("other", "2.0.0", "dev/other/mixin/TickMixin", "other$tick"));
        pack("v1.0.0", "other 2.0.0");

        project = tmp.resolve("demo-project");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.createDirectories(project.resolve("build/libs"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'fabric-loom' version '1.10.5' }\n");
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"),
                "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1.1.0\",\"mixins\":[\"demo.mixins.json\"],\"depends\":{\"other\":\">=2.0\"}}");
        Files.write(project.resolve("build/libs/demo-1.1.0.jar"), Fixture.mod("demo", "1.1.0", "dev/demo/mixin/LivingMixin", "demo$onTick"));

        Config config = Config.load();
        Config.EnvDef def = new Config.EnvDef();
        def.sources.add(server.toString());
        config.environments.put("fx", def);
        config.defaultEnv = "fx";
        config.autoSyncHours = 0.001; // due on every use: the test does not wait hours
        config.decompileAll = false; // no background decompile left running after the test
        config.save();
        try (Db db = Db.open(home, false)) {
            new Environments(config, db).sync("fx", m -> {});
        }
    }

    @AfterAll
    static void restore() {
        if (previousHome == null) System.clearProperty("envx.home");
        else System.setProperty("envx.home", previousHome);
    }

    /** The pack's own version file (BetterCompatibilityChecker) and the loader's mod list in latest.log. */
    private static void pack(String version, String other) throws IOException {
        Files.writeString(server.resolve("config/bcc.json"),
                "{\"projectID\":0,\"modpackName\":\"Fixture Pack\",\"modpackVersion\":\"" + version + "\"}");
        Files.writeString(server.resolve("logs/latest.log"), "[12:00:00] [main/INFO]: Loading 3 mods:\n\t- demo 1.0.0\n\t- minecraft 1.20.1\n\t- " + other + "\n");
    }

    private static List<String> labels() throws Exception {
        try (Db db = Db.open(home, true)) {
            return db.query("SELECT coalesce(label, '-') FROM snapshot WHERE env='fx' AND kind='sync' ORDER BY id", rs -> rs.getString(1));
        }
    }

    private static String checkedAt() throws Exception {
        try (Db db = Db.open(home, true)) {
            return db.queryString("SELECT max(coalesce(checked_at, taken_at)) FROM snapshot WHERE env='fx' AND kind='sync'");
        }
    }

    /** Waits until {@code done} holds and the background sync has released its lock (it outlives the session). */
    private static void awaitSync(java.util.concurrent.Callable<Boolean> done) throws Exception {
        Path lock = home.resolve("envs/fx/sync.lock");
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline && !(done.call() && !Files.exists(lock))) Thread.sleep(250);
    }

    /** The real MCP server as its own process, working in the project folder like an agent session. */
    private static final class Session implements AutoCloseable {
        private final Process p;
        private final PrintStream in;
        private final BufferedReader out;
        private int id;

        Session() throws IOException {
            List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xss4m",
                    "-Denvx.home=" + home, "-cp", System.getProperty("java.class.path"), "dev.envx.cli.Main", "mcp"));
            p = new ProcessBuilder(cmd).directory(project.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            in = new PrintStream(p.getOutputStream(), true, StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            request("initialize", new JsonObject());
        }

        JsonObject request(String method, JsonObject params) throws IOException {
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", ++id);
            msg.addProperty("method", method);
            msg.add("params", params);
            in.println(msg);
            String line = out.readLine();
            if (line == null) throw new IOException("MCP server exited");
            return JsonParser.parseString(line).getAsJsonObject();
        }

        String env(String filter) throws IOException {
            JsonObject params = new JsonObject();
            params.addProperty("name", "env");
            JsonObject args = new JsonObject();
            if (filter != null) args.addProperty("filter", filter);
            params.add("arguments", args);
            return request("tools/call", params).getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        }

        /** Ends the session and waits for the background syncs it started: they outlive it by design. */
        @Override
        public void close() throws Exception {
            List<ProcessHandle> children = p.descendants().toList();
            in.close();
            p.destroy();
            p.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS);
            for (ProcessHandle c : children) c.onExit().get(90, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void aPackUpdateIsPickedUpByUseTimeAutoSync() throws Exception {
        assertEquals(List.of("1.0.0"), labels());

        // The pack updates on the server: other 2.0.0 -> 2.1.0 (its injection changed), new pack version.
        Files.delete(server.resolve("mods/other-2.0.0.jar"));
        Files.write(server.resolve("mods/other-2.1.0.jar"), Fixture.mod("other", "2.1.0", "dev/other/mixin/TickMixin", "other$tickLater"));
        pack("v1.1.0", "other 2.1.0");

        try (Session running = new Session()) {
            String before = running.env(null); // first use starts the background sync; this answer comes first
            assertTrue(before.contains("Fixture Pack 1.0.0"), before);

            awaitSync(() -> labels().contains("1.1.0"));
            assertEquals(List.of("1.0.0", "1.1.0"), labels(), "auto-sync did not record the update; see " + home.resolve("logs/autosync.log"));

            String pinned = running.env(null); // a session never has answers change under it
            assertTrue(pinned.contains("Fixture Pack 1.0.0"), pinned);
        }

        try (Session fresh = new Session()) {
            String now = fresh.env(null);
            assertTrue(now.contains("Fixture Pack 1.1.0"), now);
            assertTrue(now.contains("last sync diff: ## Updated (1)"), now);
            String diff = fresh.env("diff:1.0.0..");
            assertTrue(diff.contains("other 2.0.0 -> 2.1.0"), diff);
            assertTrue(diff.contains("## Project demo 1.1.0"), diff);
            assertTrue(diff.contains("1 new, 1 gone"), diff); // the other mod's injection at the project's method changed
        }

        // Nothing changed since: the next auto-sync only records the check.
        String checked = checkedAt();
        try (Session again = new Session()) {
            again.env(null);
            awaitSync(() -> !checkedAt().equals(checked));
        }
        assertFalse(checkedAt().equals(checked), "the unchanged sync did not record its check");
        assertEquals(List.of("1.0.0", "1.1.0"), labels());
        String log = Files.readString(home.resolve("logs/autosync.log"));
        assertFalse(log.contains("auto-sync failed"), log);
    }
}
