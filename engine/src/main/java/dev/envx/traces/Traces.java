package dev.envx.traces;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.envx.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads agent sessions where the tools keep them (Codex rollouts, Claude Code transcripts), in place and read-only,
 * into a common shape: which shell commands and envx calls a session made, how big their outputs were, and whether an
 * envx answer was empty, capped or an error. Nothing is copied or stored.
 */
public final class Traces {
    private Traces() {}

    /** One tool call: a shell command, an envx tool call, or anything else (edits, web, ...). */
    public record Call(Instant ts, String kind, String name, String text, int outChars, boolean empty, boolean capped, boolean error) {
        boolean shell() {
            return kind.equals("shell");
        }

        public boolean envx() {
            return kind.equals("envx");
        }
    }

    /** One agent session. {@code file} is the tool's own session file (provenance only). */
    public record Session(String tool, String id, Path file, String cwd, Instant start, String model, List<Call> calls) {}

    /** Sessions read and why others were left out. */
    public static final class Result {
        public final List<Session> sessions = new ArrayList<>();
        public int headless, denied, envxRepo, unreadable;
    }

    private static final Pattern CAPPED = Pattern.compile("more line\\(s\\) omitted");
    private static final Pattern EMPTY = Pattern.compile("(?m)(^|\\\\n|\")\\s*No (?:matches|text matches|class|member|mod|mixin|refs|references|declared|environment|such)");

    /** Where Codex keeps its sessions ({@code CODEX_HOME}, else {@code ~/.codex}). */
    static Path codexSessions() {
        String home = System.getenv("CODEX_HOME");
        return (home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".codex")).resolve("sessions");
    }

    /** Where Claude Code keeps its transcripts ({@code CLAUDE_CONFIG_DIR}, else {@code ~/.claude}). */
    static Path claudeProjects() {
        String home = System.getenv("CLAUDE_CONFIG_DIR");
        return (home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".claude")).resolve("projects");
    }

    /** Sessions active since {@code since}, from where each tool keeps them on this PC. */
    public static Result recent(Config config, Instant since) {
        return read(config, since, codexSessions(), claudeProjects());
    }

    /** Sessions active since {@code since}; session files not modified since then are not opened. */
    static Result read(Config config, Instant since, Path codex, Path claude) {
        Result r = new Result();
        for (Path f : files(codex, since, true)) add(config, r, () -> codex(f));
        for (Path f : files(claude, since, false)) add(config, r, () -> claude(f));
        r.sessions.removeIf(s -> s.start() != null && s.start().isBefore(since) && s.calls().stream().allMatch(c -> c.ts() == null || c.ts().isBefore(since)));
        return r;
    }

    private interface Reader {
        Session read() throws IOException;
    }

    private static void add(Config config, Result r, Reader reader) {
        Session s;
        try {
            s = reader.read();
        } catch (IOException | RuntimeException e) {
            r.unreadable++;
            return;
        }
        if (s == null) {
            r.headless++;
            return;
        }
        if (s.cwd() != null && !s.cwd().isBlank()) {
            Path cwd = Path.of(s.cwd());
            if (config.isDenied(cwd)) { // never mined, beyond the header that says where it ran
                r.denied++;
                return;
            }
            if (Files.isDirectory(cwd.resolve("engine/src/main/java/dev/envx"))) { // envx's own development is not agent work on mods
                r.envxRepo++;
                return;
            }
        }
        r.sessions.add(s);
    }

    private static List<Path> files(Path root, Instant since, boolean recursive) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> w = recursive ? Files.walk(root) : Files.walk(root, 2)) {
            return w.filter(f -> f.toString().endsWith(".jsonl")).filter(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant().isAfter(since);
                } catch (IOException e) {
                    return false;
                }
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ Codex

    private static final Pattern JS_CALL = Pattern.compile("tools\\.([A-Za-z_0-9]+)\\s*\\(");

    /** A Codex rollout; null for a headless run ({@code codex exec}: benchmarks and scripts). */
    static Session codex(Path f) throws IOException {
        String id = null, cwd = null, model = null;
        Instant start = null;
        List<Call> calls = new ArrayList<>();
        Map<String, int[]> byCallId = new HashMap<>(); // call_id -> {first index, count}
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                boolean meta = line.contains("\"session_meta\""), ctx = !meta && model == null && line.contains("\"turn_context\"");
                boolean call = line.contains("\"custom_tool_call\""), out = line.contains("\"custom_tool_call_output\"");
                if (!meta && !ctx && !call && !out) continue;
                JsonObject d = JsonParser.parseString(line).getAsJsonObject();
                JsonObject p = d.has("payload") && d.get("payload").isJsonObject() ? d.getAsJsonObject("payload") : new JsonObject();
                Instant ts = instant(str(d, "timestamp"));
                if (meta && "session_meta".equals(str(d, "type"))) {
                    if ("codex_exec".equals(str(p, "originator"))) return null;
                    id = str(p, "id");
                    cwd = str(p, "cwd");
                    start = instant(str(p, "timestamp"));
                } else if (ctx && "turn_context".equals(str(d, "type"))) {
                    model = str(p, "model");
                } else if ("custom_tool_call".equals(str(p, "type"))) {
                    String js = str(p, "input");
                    if (js == null) continue;
                    int first = calls.size();
                    Matcher m = JS_CALL.matcher(js);
                    while (m.find()) {
                        String name = m.group(1);
                        String args = argsText(js, m.end());
                        if (name.equals("exec_command")) calls.add(new Call(ts, "shell", "shell", jsString(args, "cmd"), 0, false, false, false));
                        else if (name.startsWith("mcp__envx__")) calls.add(new Call(ts, "envx", name.substring(11), args, 0, false, false, false));
                        else calls.add(new Call(ts, "other", name, "", 0, false, false, false));
                    }
                    if (calls.size() > first) byCallId.put(str(p, "call_id"), new int[]{first, calls.size() - first});
                } else if ("custom_tool_call_output".equals(str(p, "type"))) {
                    int[] at = byCallId.get(str(p, "call_id"));
                    if (at == null) continue;
                    String text = text(p.get("output"));
                    for (int i = at[0]; i < at[0] + at[1]; i++) {
                        Call c = calls.get(i);
                        boolean alone = at[1] == 1; // flags are only certain when the output belongs to one call
                        calls.set(i, new Call(c.ts(), c.kind(), c.name(), c.text(), text.length() / at[1],
                                alone && EMPTY.matcher(text).find(), alone && CAPPED.matcher(text).find(), alone && text.contains("isError")));
                    }
                }
            }
        }
        return new Session("codex", id, f, cwd, start, model, calls);
    }

    /** The source text of a JS call's arguments: from after '(' to the matching ')'. */
    static String argsText(String js, int from) {
        int depth = 1;
        char quote = 0;
        for (int i = from; i < js.length(); i++) {
            char ch = js.charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'' || ch == '`') quote = ch;
            else if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') {
                if (--depth == 0) return js.substring(from, i).trim();
            }
        }
        return js.substring(from).trim();
    }

    /** The string value of {@code key:"..."} in JS object source (any quote style), unescaped; else the whole text. */
    static String jsString(String args, String key) {
        Matcher m = Pattern.compile("[\"']?\\b" + key + "[\"']?\\s*:\\s*([\"'`])").matcher(args);
        if (!m.find()) return args;
        char quote = m.group(1).charAt(0);
        StringBuilder sb = new StringBuilder();
        for (int i = m.end(); i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '\\' && i + 1 < args.length()) {
                char n = args.charAt(++i);
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> n;
                });
            } else if (ch == quote) break;
            else sb.append(ch);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Claude Code

    /** A Claude Code transcript; null for a headless run ({@code claude -p}: benchmarks and scripts). */
    static Session claude(Path f) throws IOException {
        String id = null, cwd = null, model = null;
        Instant start = null;
        List<Call> calls = new ArrayList<>();
        Map<String, Integer> byUseId = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.contains("\"tool_use\"") && !line.contains("\"tool_result\"") && id != null) continue;
                JsonObject d;
                try {
                    d = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if ("sdk-cli".equals(str(d, "entrypoint"))) return null;
                if (id == null && str(d, "sessionId") != null) {
                    id = str(d, "sessionId");
                    start = instant(str(d, "timestamp"));
                }
                if (cwd == null) cwd = str(d, "cwd");
                JsonObject msg = d.has("message") && d.get("message").isJsonObject() ? d.getAsJsonObject("message") : null;
                if (msg == null || !msg.has("content") || !msg.get("content").isJsonArray()) continue;
                if (model == null && str(msg, "model") != null) model = str(msg, "model");
                Instant ts = instant(str(d, "timestamp"));
                for (JsonElement e : msg.getAsJsonArray("content")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject c = e.getAsJsonObject();
                    if ("tool_use".equals(str(c, "type"))) {
                        String name = str(c, "name");
                        JsonObject input = c.has("input") && c.get("input").isJsonObject() ? c.getAsJsonObject("input") : new JsonObject();
                        byUseId.put(str(c, "id"), calls.size());
                        if ("Bash".equals(name) || "PowerShell".equals(name)) calls.add(new Call(ts, "shell", "shell", str(input, "command"), 0, false, false, false));
                        else if (name != null && name.startsWith("mcp__envx__")) calls.add(new Call(ts, "envx", name.substring(11), input.toString(), 0, false, false, false));
                        else calls.add(new Call(ts, "other", name, "", 0, false, false, false));
                    } else if ("tool_result".equals(str(c, "type"))) {
                        Integer at = byUseId.get(str(c, "tool_use_id"));
                        if (at == null) continue;
                        String text = text(c.get("content"));
                        boolean error = c.has("is_error") && c.get("is_error").getAsBoolean();
                        Call old = calls.get(at);
                        calls.set(at, new Call(old.ts(), old.kind(), old.name(), old.text(), text.length(),
                                EMPTY.matcher(text).find(), CAPPED.matcher(text).find(), error));
                    }
                }
            }
        }
        return id == null ? new Session("claude", f.getFileName().toString(), f, cwd, start, model, calls)
                : new Session("claude", id, f, cwd, start, model, calls);
    }

    // ------------------------------------------------------------------ helpers

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Instant instant(String iso) {
        try {
            return iso == null ? null : Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Text of a tool output: a string, or a list of {type, text} parts. */
    private static String text(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : (JsonArray) e) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) sb.append(part.getAsJsonObject().get("text").getAsString());
                else if (part.isJsonPrimitive()) sb.append(part.getAsString());
            }
            return sb.toString();
        }
        return e.toString();
    }
}
