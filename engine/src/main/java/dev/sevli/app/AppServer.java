package dev.sevli.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.Version;
import dev.sevli.catalog.Baselines;
import dev.sevli.catalog.Catalog;
import dev.sevli.env.AutoSync;
import dev.sevli.query.FullDecompile;
import dev.sevli.store.Db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code sevli app-server}: the desktop app's connection to the engine (ADR 0013). JSON lines on stdin/stdout, like
 * {@code sevli api} (docs/api.md) but for the app only, so it may change with the app. Requests
 * {@code {"id":…, "op":"…", …}}; answers {@code {"app":1, "id":…, "ok":true, "result":…}} or
 * {@code {"app":1, "id":…, "ok":false, "error":{"code":…, "message":…}}}; long operations also send
 * {@code {"app":1, "event":"progress", "id":…, "line":…}} before their answer. Writes (installs, syncs) run as
 * separate sevli processes, as from the command line (ADR 0002).
 */
public final class AppServer {
    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final PrintStream out;
    private Config config;
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    AppServer(Config config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    public static int run(InputStream in, PrintStream out) throws IOException {
        AppServer s = new AppServer(Config.load(), out);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = r.readLine()) != null; ) {
            if (!line.isBlank()) s.handle(line);
        }
        s.running.values().forEach(Process::destroy); // the app closed: stop what it started
        return 0;
    }

    void handle(String line) {
        JsonElement id = null;
        try {
            JsonObject req = JsonParser.parseString(line).getAsJsonObject();
            id = req.get("id");
            String op = req.has("op") ? req.get("op").getAsString() : "";
            switch (op) {
                case "hello" -> ok(id, hello());
                case "status" -> ok(id, status());
                case "catalog" -> ok(id, catalog());
                case "home.get" -> ok(id, home());
                case "home.set" -> ok(id, setHome(str(req, "dir")));
                case "install" -> install(id, str(req, "target"), "catalog", "install");
                case "accept" -> install(id, str(req, "env"), "accept"); // the user's click on "N jars are now supported"
                case "cancel" -> ok(id, cancel(str(req, "target")));
                case "agents" -> ok(id, Agents.state(config));
                case "agents.activity" -> background(id, () -> Agents.activity(config));
                default -> fail(id, "unknown_op", "unknown op '" + op + "'");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail(id, "bad_request", e.getMessage());
        } catch (Exception e) {
            fail(id, "internal", String.valueOf(e));
        }
    }

    // ------------------------------------------------------------------ operations

    private JsonObject hello() {
        JsonObject o = new JsonObject();
        o.addProperty("sevli", Version.VALUE);
        o.addProperty("protocol", VERSION);
        o.add("home", home());
        return o;
    }

    private JsonObject home() {
        JsonObject o = new JsonObject();
        o.addProperty("dir", config.home().toString());
        o.addProperty("exists", Files.isDirectory(config.home()));
        o.addProperty("default", Path.of(System.getProperty("user.home"), ".sevli").toString());
        return o;
    }

    /** Chooses where data goes before the first install; afterwards moving it is a separate operation. */
    private JsonObject setHome(String dir) throws IOException {
        if (dir == null || dir.isBlank()) throw new IllegalArgumentException("dir is required");
        if (Config.fixedHome() != null) {
            throw new IllegalStateException("the data location is fixed by SEVLI_HOME (or -Dsevli.home) for this run");
        }
        Path target = Path.of(dir).toAbsolutePath().normalize();
        if (Files.exists(config.home().resolve("config.json")) && !target.equals(config.home())) {
            throw new IllegalStateException("data already lives in " + config.home() + "; moving it is done from Settings");
        }
        Config.writePointer(target);
        config = Config.load();
        return home();
    }

    private JsonObject status() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("sevli", Version.VALUE);
        o.add("home", home());
        String agents;
        try {
            agents = dev.sevli.cli.AgentSetup.summaryLine(config);
        } catch (Exception e) {
            agents = "unknown (" + e.getMessage() + ")";
        }
        o.addProperty("agents", agents);
        o.add("baselines", baselines(null));
        JsonArray envs = new JsonArray();
        if (Files.exists(config.home().resolve("index.sqlite"))) {
            try (Db db = Db.open(config.home(), true)) {
                for (var e : config.environments.entrySet()) {
                    JsonObject env = new JsonObject();
                    env.addProperty("name", e.getKey());
                    env.addProperty("default", e.getKey().equals(config.defaultEnv));
                    env.addProperty("minecraft", e.getValue().minecraft);
                    env.addProperty("loader", e.getValue().platform);
                    var rows = db.query("SELECT id, coalesce(checked_at, taken_at), label FROM snapshot WHERE env=? AND kind='sync' ORDER BY id DESC LIMIT 1",
                            rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, e.getKey());
                    if (!rows.isEmpty()) {
                        env.addProperty("snapshot", (long) rows.getFirst()[0]);
                        env.addProperty("checkedAt", (String) rows.getFirst()[1]);
                        env.addProperty("label", (String) rows.getFirst()[2]);
                        var cov = dev.sevli.env.Environments.coverage(db, (long) rows.getFirst()[0]);
                        env.addProperty("jarsIndexed", cov.indexed());
                        env.addProperty("jarsTotal", cov.total());
                        env.addProperty("newlySupported", cov.count("newly_supported"));
                        JsonArray un = new JsonArray();
                        for (var u : cov.unindexed().subList(0, Math.min(200, cov.unindexed().size()))) {
                            JsonObject j = new JsonObject();
                            j.addProperty("file", u.relPath().substring(u.relPath().lastIndexOf('/') + 1));
                            j.addProperty("modId", u.modId());
                            j.addProperty("version", u.version());
                            j.addProperty("reason", u.reason());
                            un.add(j);
                        }
                        env.add("unindexed", un);
                        int[] c = FullDecompile.progress(config, e.getKey());
                        env.addProperty("sourceJarsDone", c[0]);
                        env.addProperty("sourceJarsTotal", c[1]);
                    }
                    env.addProperty("syncRunning", AutoSync.running(config.home(), e.getKey()));
                    envs.add(env);
                }
            }
        }
        o.add("environments", envs);
        o.addProperty("backgroundRunning", FullDecompile.isRunning(config.home()));
        return o;
    }

    private JsonObject catalog() {
        JsonObject o = new JsonObject();
        Catalog c = null;
        try {
            c = Catalog.load(config.catalogUrl);
        } catch (IOException | RuntimeException e) {
            o.addProperty("error", "the catalog is not reachable right now (" + e.getMessage() + ")");
        }
        o.add("baselines", baselines(c));
        JsonArray packs = new JsonArray();
        if (c != null) {
            for (Catalog.Pack p : c.packs) {
                JsonObject j = GSON.toJsonTree(p).getAsJsonObject();
                j.addProperty("supported", Baselines.byId(p.baseline()).isPresent());
                j.addProperty("installed", config.environments.containsKey(p.id()));
                packs.add(j);
            }
        }
        o.add("packs", packs);
        return o;
    }

    /** This build's baselines, plus ones the catalog lists that need a newer sevli. */
    private JsonArray baselines(Catalog c) {
        JsonArray a = new JsonArray();
        for (Baselines.Baseline b : Baselines.SUPPORTED) {
            JsonObject j = new JsonObject();
            j.addProperty("id", b.id());
            j.addProperty("name", b.name());
            j.addProperty("minecraft", b.minecraft());
            j.addProperty("loader", b.loader());
            j.addProperty("downloadBytes", b.downloadBytes());
            j.addProperty("diskBytes", b.diskBytes());
            j.addProperty("installed", b.installed(config.home()));
            j.addProperty("supported", true);
            a.add(j);
        }
        if (c != null) {
            for (Catalog.CatalogBaseline b : c.baselines) {
                if (Baselines.byId(b.id()).isPresent()) continue;
                JsonObject j = GSON.toJsonTree(b).getAsJsonObject();
                j.addProperty("installed", false);
                j.addProperty("supported", false); // shown as "needs a newer sevli"
                a.add(j);
            }
        }
        return a;
    }

    /**
     * Runs a writing command ({@code sevli catalog install <target>}, {@code sevli accept <env>}) as its own process,
     * forwarding its output as progress events.
     */
    private void install(JsonElement id, String target, String... command) throws IOException {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target is required");
        if (running.containsKey(target)) throw new IllegalStateException(target + " is already being installed");
        List<String> args = new ArrayList<>(List.of(command));
        args.add(target);
        List<String> cmd = new ArrayList<>(AutoSync.command(config, args.toArray(String[]::new)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        running.put(target, p);
        Thread t = new Thread(() -> {
            List<String> tail = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    progress(id, l);
                    tail.add(l);
                    if (tail.size() > 5) tail.removeFirst();
                }
                int exit = p.waitFor();
                config = Config.load(); // the install may have created the data home and config
                if (exit == 0) {
                    JsonObject res = new JsonObject();
                    res.addProperty("target", target);
                    ok(id, res);
                } else {
                    fail(id, "install_failed", String.join("\n", tail));
                }
            } catch (Exception e) {
                fail(id, "install_failed", String.valueOf(e));
            } finally {
                running.remove(target);
            }
        }, "install-" + target);
        t.setDaemon(true);
        t.start();
    }

    /** Answers later from its own thread, so slow reads (agent sessions) do not hold up other requests. */
    private void background(JsonElement id, java.util.function.Supplier<JsonElement> work) {
        Thread t = new Thread(() -> {
            try {
                ok(id, work.get());
            } catch (RuntimeException e) {
                fail(id, "internal", String.valueOf(e));
            }
        }, "app-request");
        t.setDaemon(true);
        t.start();
    }

    private JsonObject cancel(String target) {
        Process p = target == null ? null : running.get(target);
        JsonObject o = new JsonObject();
        o.addProperty("cancelled", p != null);
        if (p != null) p.destroy();
        return o;
    }

    // ------------------------------------------------------------------ transport

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private void progress(JsonElement id, String line) {
        JsonObject o = new JsonObject();
        o.addProperty("app", VERSION);
        o.addProperty("event", "progress");
        o.add("id", id);
        o.addProperty("line", line);
        send(o);
    }

    private void ok(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("app", VERSION);
        o.add("id", id);
        o.addProperty("ok", true);
        o.add("result", result);
        send(o);
    }

    private void fail(JsonElement id, String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("app", VERSION);
        o.add("id", id);
        o.addProperty("ok", false);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        send(o);
    }

    private synchronized void send(JsonObject o) {
        out.println(GSON.toJson(o));
        out.flush();
    }
}
