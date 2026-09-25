package dev.envx.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;

/**
 * One JSON line per tool call in {@code <home>/logs/calls-YYYY-MM.jsonl}: which tool, what was
 * asked, how big the answer was, how long it took. Local only. It exists so that real use shows
 * which answers lead to follow-up calls. Logging must never break or slow a query, so every failure
 * is swallowed; the cost is one small append.
 */
final class CallLog {
    /** Groups calls from one process; an MCP process corresponds to one agent session. */
    private static final String SESSION = ProcessHandle.current().pid() + "-" + Long.toString(System.currentTimeMillis(), 36);

    /** A headless benchmark run's id ({@code bench/agents.py} sets ENVX_RUN for the MCP server it starts). */
    private static final String RUN = System.getenv("ENVX_RUN");

    private CallLog() {}

    static void record(Path home, String via, String tool, JsonObject args, String env, Path cwd, Tools.Result r, long millis) {
        try {
            JsonObject line = new JsonObject();
            line.addProperty("ts", Instant.now().toString());
            line.addProperty("via", via);
            line.addProperty("session", SESSION);
            line.addProperty("v", dev.envx.Version.VALUE); // which envx answered, for comparing sessions across releases
            if (RUN != null) line.addProperty("run", RUN);
            line.addProperty("env", env);
            line.addProperty("cwd", cwd.toString());
            line.addProperty("tool", tool);
            JsonObject a = new JsonObject();
            for (var e : args.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString();
                a.addProperty(e.getKey(), v.length() > 200 ? v.substring(0, 200) + "…" : v);
            }
            line.add("args", a);
            line.addProperty("chars", r.text().length());
            line.addProperty("hash", Integer.toHexString(r.text().hashCode())); // same answer to differently worded calls
            line.addProperty("ms", millis);
            line.addProperty("error", r.error());
            Path dir = home.resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("calls-" + YearMonth.now() + ".jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // measurement must never affect the answer
        }
    }
}
