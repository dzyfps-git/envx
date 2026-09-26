package dev.sevli.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.env.AutoSync;
import dev.sevli.query.Scope;
import dev.sevli.store.Db;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC 2.0): initialize, tools/list,
 * tools/call, ping. Hand-written rather than using the MCP SDK to keep dependencies and startup
 * small (see docs/adr/0003-hand-rolled-mcp.md). It opens the index read-only and never indexes,
 * so it starts quickly and many sessions can run side by side.
 */
public final class McpServer {
    private static final String VERSION = dev.sevli.Version.VALUE;
    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    static final String INSTRUCTIONS = """
            sevli: local index of this project's Minecraft/Fabric environment (the live server's mods, Minecraft 1.20.1, Yarn names).
            Use it instead of unzipping jars, running javap, searching Gradle caches or decompiling by hand.
            find/outline first; source for code (Class.a,b fetches several members at once); refs/mixins/check_mixins for callers and injections.
            Add mod:<id> to a query to search inside specific mods. Answers are size-capped and state what they cover.
            Server crashes, log errors and failed mixins: env filter=errors[:text] (grep scope=logs for raw lines). Pack updates: env filter=diff:<old>..""";

    private final PrintStream out;
    private final Path cwd;
    private Tools.Ctx ctx;

    private McpServer(PrintStream out, Path cwd) {
        this.out = out;
        this.cwd = cwd;
    }

    public static void run() throws Exception {
        PrintStream stdout = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // nothing but protocol messages may reach stdout
        McpServer server = new McpServer(stdout, Path.of("").toAbsolutePath());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonObject msg;
            try {
                msg = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                server.send(error(null, -32700, "Parse error"));
                continue;
            }
            JsonObject reply = server.handle(msg);
            if (reply != null) server.send(reply);
        }
    }

    private JsonObject handle(JsonObject msg) {
        JsonElement id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").getAsString() : null;
        if (id == null || method == null) return null; // notification or stray response
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> result(id, toolsCall(params));
                case "ping" -> result(id, new JsonObject());
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            return error(id, -32603, e.toString());
        }
    }

    private JsonObject initialize(JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "2025-06-18");
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "sevli");
        info.addProperty("version", VERSION);
        r.add("serverInfo", info);
        r.addProperty("instructions", INSTRUCTIONS);
        return r;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        for (Tools.Tool t : Tools.ALL.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", Tools.schema(t));
            JsonObject ann = new JsonObject();
            ann.addProperty("readOnlyHint", true);
            o.add("annotations", ann);
            tools.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("tools", tools);
        return r;
    }

    private JsonObject toolsCall(JsonObject params) throws Exception {
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        if (ctx == null) { // opened on first use so the handshake is instant
            Config config = Config.load();
            ctx = new Tools.Ctx(config, Db.open(config.home(), true), "mcp");
            Scope.pinCurrentSnapshot(); // a background sync never changes answers mid-session
            AutoSync.maybeStart(config, config.envFor(cwd)); // use-time sync: never waits, never writes from here
        }
        Tools.Result res = Tools.call(ctx, name, args, cwd);
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", res.text());
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject r = new JsonObject();
        r.add("content", content);
        r.addProperty("isError", res.error());
        return r;
    }

    private void send(JsonObject o) {
        out.print(GSON.toJson(o) + "\n"); // LF only; println would emit CRLF on Windows
        out.flush();
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o;
    }
}
