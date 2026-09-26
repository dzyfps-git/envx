package dev.sevli.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.cli.AgentSetup;
import dev.sevli.traces.Traces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the desktop app shows about each coding agent: installed, sevli on or off, the model and reasoning effort it is
 * set to, and how much it used sevli lately. Settings files are read for those keys only; sign-in and credential files
 * are never opened.
 */
public final class Agents {
    static final int DAYS = 14;

    private Agents() {}

    /** Installed, sevli state, model and effort; fast (a few small files). */
    static JsonArray state(Config config) {
        AgentSetup.AgentState s;
        try {
            s = AgentSetup.state(config);
        } catch (IOException | RuntimeException e) {
            s = new AgentSetup.AgentState(false, null, 0, 0);
        }
        JsonArray a = new JsonArray();
        a.add(claude(s));
        a.add(codex(s));
        return a;
    }

    /** Claude Code is on this PC: its settings folder or its program exists. */
    public static boolean claudeInstalled() {
        Path exe = Path.of(System.getProperty("user.home"), ".local", "bin", windows() ? "claude.exe" : "claude");
        return Files.isDirectory(dir("CLAUDE_CONFIG_DIR", ".claude")) || Files.exists(exe) || onPath("claude");
    }

    /** Codex is on this PC: its settings folder or its program exists. */
    public static boolean codexInstalled() {
        return Files.isDirectory(dir("CODEX_HOME", ".codex")) || onPath("codex");
    }

    private static JsonObject claude(AgentSetup.AgentState s) {
        Path home = dir("CLAUDE_CONFIG_DIR", ".claude");
        JsonObject o = base("claude", "Claude Code", claudeInstalled());
        o.addProperty("sevli", s.claudeOn());
        o.addProperty("registered", s.claudeOn());
        try {
            Path f = home.resolve("settings.json");
            if (Files.isRegularFile(f)) {
                JsonObject j = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                String model = str(j.get("model"));
                String effort = str(j.get("effortLevel"));
                if (effort == null && model != null && j.get("modelSettings") instanceof JsonObject per // set per model
                        && per.get(model) instanceof JsonObject m) {
                    effort = str(m.get("effortLevel"));
                }
                o.addProperty("model", model);
                o.addProperty("effort", effort);
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable settings: shown as unknown
        }
        o.addProperty("instructionFiles", s.instructionFiles());
        o.addProperty("projects", s.projects());
        return o;
    }

    private static JsonObject codex(AgentSetup.AgentState s) {
        Path home = dir("CODEX_HOME", ".codex");
        JsonObject o = base("codex", "Codex", codexInstalled());
        o.addProperty("sevli", Boolean.TRUE.equals(s.codexOn()));
        o.addProperty("registered", s.codexOn() != null);
        try {
            Path f = home.resolve("config.toml");
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f)) { // top-level keys only: stop at the first [table]
                    String t = line.trim();
                    if (t.startsWith("[")) break;
                    String model = tomlString(t, "model");
                    if (model != null) o.addProperty("model", model);
                    String effort = tomlString(t, "model_reasoning_effort");
                    if (effort != null) o.addProperty("effort", effort);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable config: shown as unknown
        }
        o.addProperty("instructionFiles", s.instructionFiles());
        o.addProperty("projects", s.projects());
        return o;
    }

    /**
     * Per agent, sessions and sevli calls on each of the last {@link #DAYS} days (oldest first), from the sessions each
     * tool keeps on this PC (the same reading as {@code sevli insights}; sevli's own development excluded).
     */
    static JsonObject activity(Config config) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant since = today.minusDays(DAYS - 1).atStartOfDay(zone).toInstant();
        Traces.Result r = Traces.recent(config, since);
        JsonObject out = new JsonObject();
        for (String tool : List.of("claude", "codex")) {
            int[] sessions = new int[DAYS];
            int[] calls = new int[DAYS];
            String lastModel = null;
            Instant last = null;
            List<Traces.Session> mine = r.sessions.stream().filter(x -> x.tool().equals(tool))
                    .sorted(Comparator.comparing(x -> Objects.requireNonNullElse(x.start(), Instant.EPOCH))).toList();
            for (Traces.Session x : mine) {
                if (x.start() != null) {
                    int d = day(x.start(), today, zone);
                    if (d >= 0) sessions[d]++;
                    if (x.model() != null) lastModel = x.model();
                    last = x.start();
                }
                for (Traces.Call c : x.calls()) {
                    if (!c.sevli() || c.ts() == null) continue;
                    int d = day(c.ts(), today, zone);
                    if (d >= 0) calls[d]++;
                }
            }
            JsonObject t = new JsonObject();
            t.add("sessions", ints(sessions));
            t.add("sevliCalls", ints(calls));
            t.addProperty("lastModel", lastModel);
            t.addProperty("lastSession", last == null ? null : last.toString());
            out.add(tool, t);
        }
        out.addProperty("days", DAYS);
        return out;
    }

    private static int day(Instant ts, LocalDate today, ZoneId zone) {
        long back = ChronoUnit.DAYS.between(ts.atZone(zone).toLocalDate(), today);
        return back < 0 || back >= DAYS ? -1 : (int) (DAYS - 1 - back);
    }

    private static JsonArray ints(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    private static JsonObject base(String id, String name, boolean installed) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("installed", installed);
        return o;
    }

    private static Path dir(String env, String dflt) {
        String v = System.getenv(env);
        return v != null && !v.isBlank() ? Path.of(v) : Path.of(System.getProperty("user.home"), dflt);
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isBlank()) continue;
            for (String ext : windows() ? List.of(".exe", ".cmd", ".bat", "") : List.of("")) {
                try {
                    if (Files.isRegularFile(Path.of(d, exe + ext))) return true;
                } catch (RuntimeException ignored) {
                    // malformed PATH entry
                }
            }
        }
        return false;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** {@code key = "value"} on one TOML line, else null. */
    static String tomlString(String line, String key) {
        if (!line.startsWith(key)) return null;
        String rest = line.substring(key.length()).trim();
        if (!rest.startsWith("=")) return null;
        rest = rest.substring(1).trim();
        if (rest.length() < 2) return null;
        char q = rest.charAt(0);
        if (q != '"' && q != '\'') return null;
        int end = rest.indexOf(q, 1);
        return end < 0 ? null : rest.substring(1, end);
    }
}
