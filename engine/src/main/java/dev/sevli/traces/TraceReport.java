package dev.sevli.traces;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.env.SecretFilter;
import dev.sevli.store.Db;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code sevli insights}: what agents spent effort rediscovering, mined from their own session files (ADR 0012).
 * Engine improvements come first: each finding names the sevli answer that should have replaced the work. Nothing is
 * stored; the report is computed on request and printed.
 */
public final class TraceReport {
    /** Shell work sevli is meant to replace, most specific first; each with the sevli way to do it. */
    record Category(String name, Pattern pattern, String sevliWay) {}

    static final List<Category> CATEGORIES = List.of(
            // other loaders first: that work is demand for a platform sevli does not cover yet, not a missed sevli answer
            new Category("Forge/NeoForge work", Pattern.compile("(?i)forge_gradle|minecraftforge|neoforge|mods\\.toml"), "not covered yet (sevli is Fabric-only)"),
            new Category("decompiling", Pattern.compile("(?i)vineflower|fernflower|\\bcfr\\b|procyon|quiltflower|genSources|ilspy"), "source"),
            new Category("javap", Pattern.compile("(?i)\\bjavap\\b"), "outline / source"),
            new Category("mapping lookups", Pattern.compile("(?i)mappings\\.tiny|\\.tiny\\b|\\bclass_\\d{3,}|\\bmethod_\\d{3,}|\\bfield_\\d{3,}"), "find (any name form)"),
            // jars only: comparing a datapack .zip is the agent's own work
            new Category("reading jars", Pattern.compile("(?is)^(?=.*\\.jar\\b).*(Expand-Archive|ZipFile|System\\.IO\\.Compression|\\bjar\\s+-?[xt]v?f|\\bunzip\\b|7z\\s+[xel]\\b|zipfile)"), "find / outline / grep scope=resources"),
            new Category("mixin configs", Pattern.compile("(?i)mixins?\\.json|refmap"), "mixins / check_mixins"),
            new Category("mod metadata", Pattern.compile("(?i)fabric\\.mod\\.json"), "find mod:<id> / env"),
            new Category("Gradle caches", Pattern.compile("(?i)\\.gradle[\\\\/]caches|loom-cache|fabric-loom"), "find / source"),
            // a server's own logs: a drive or share root, at most one folder deep (S:\logs, C:\MyServer\logs, \\host\share\logs);
            // a project's test run (.\run\logs, ...\integration\x\logs) is the agent checking its own work, which sevli does not index
            new Category("server logs", Pattern.compile("(?i)(?<![\\w.])(?:[A-Z]:|\\\\\\\\[\\w.-]+\\\\[\\w.$ -]+)[\\\\/]+(?:[^\\\\/'\"\\s]+[\\\\/]+)?"
                    + "(?:logs[\\\\/]+(?:latest|debug)\\.log|crash-reports)"), "env filter=errors / grep scope=logs"),
            // a search whose target is decompiled or sources code; searching the project's own src/ is not rediscovery
            new Category("searching mod code", Pattern.compile("(?i)(\\brg\\b|\\bgrep\\b|Select-String|findstr)[^|;\\n]*(decomp|genSources|-sources\\b|remapped)"), "grep scope=source / refs"),
            new Category("listing mods", Pattern.compile("(?i)(Get-ChildItem|\\bls\\b|\\bdir\\b|\\bfind\\b)[^|;]*[\\\\/]mods\\b"), "env"));

    private static final Pattern JAR = Pattern.compile("([A-Za-z][A-Za-z0-9_+.-]*?)(?:[-_](?:mc)?\\d[\\w.+-]*)?\\.jar\\b");
    /** Class-like words: two or more humps (LivingEntity), or the last part of a qualified name (a.b.Entity, a/b/Entity). */
    private static final Pattern WORD = Pattern.compile("(?:(?<=[a-z0-9][.$])[A-Z][A-Za-z0-9]{2,}|\\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]+)\\b");
    /** Shell and .NET noise removed before looking for class names: [Types], Verb-Noun cmdlets, -Parameters, System.* names. */
    private static final Pattern SHELL_NOISE = Pattern.compile("\\[[^\\]]*\\]|\\b[A-Z][A-Za-z]+-[A-Z]\\w*|(?<![\\w.])-[A-Za-z]\\w*"
            + "|\\bSystem(?:\\.\\w+)+|New-Object\\s+\\S+|\\b(?:Program Files|Eclipse Adoptium)\\b|\\$[A-Za-z_]\\w*|\\.[a-z]+\\b(?![./\\\\])");

    private final Config config;
    private final Traces.Result traces;
    private final Instant since;
    private final Set<String> classNames = new HashSet<>();
    private final Map<String, Boolean> classCache = new HashMap<>();
    private final Db db;
    /** The first sevli call in the window; work before it could not have used sevli. */
    private Instant firstSevli;

    private TraceReport(Config config, Traces.Result traces, Instant since, Db db) {
        this.config = config;
        this.traces = traces;
        this.since = since;
        this.db = db;
    }

    public static String run(Config config, int days) throws SQLException {
        return run(config, days, Traces.codexSessions(), Traces.claudeProjects());
    }

    static String run(Config config, int days, Path codexSessions, Path claudeProjects) throws SQLException {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Traces.Result t = Traces.read(config, since, codexSessions, claudeProjects);
        Db db = Files.exists(config.home().resolve("index.sqlite")) ? Db.open(config.home(), true) : null;
        try {
            return new TraceReport(config, t, since, db).render(days);
        } finally {
            if (db != null) db.close();
        }
    }

    static Category classify(String cmd) {
        if (cmd == null) return null;
        for (Category c : CATEGORIES) if (c.pattern().matcher(cmd).find()) return c;
        return null;
    }

    // ------------------------------------------------------------------ rendering

    private String render(int days) throws SQLException {
        StringBuilder out = new StringBuilder();
        List<Traces.Session> ss = traces.sessions;
        long shell = count(ss, Traces.Call::shell), sevli = count(ss, Traces.Call::sevli);
        long withSevli = ss.stream().filter(s -> s.calls().stream().anyMatch(Traces.Call::sevli)).count();
        out.append("# sevli trace report: the last ").append(days).append(" days\n");
        out.append(ss.size()).append(" agent sessions (").append(ss.stream().filter(s -> s.tool().equals("codex")).count()).append(" Codex, ")
                .append(ss.stream().filter(s -> s.tool().equals("claude")).count()).append(" Claude Code), ").append(withSevli)
                .append(" of them used sevli; ").append(shell).append(" shell commands, ").append(sevli).append(" sevli calls.\n");
        out.append("Left out: ").append(traces.headless).append(" headless runs (benchmarks, scripts), ").append(traces.denied)
                .append(" in denied folders, ").append(traces.sevliRepo).append(" in sevli's own repository")
                .append(traces.unreadable > 0 ? ", " + traces.unreadable + " unreadable" : "").append(".\n");
        firstSevli = ss.stream().flatMap(s -> s.calls().stream()).filter(c -> c.sevli() && c.ts() != null).map(Traces.Call::ts)
                .min(Comparator.naturalOrder()).orElse(null);
        if (firstSevli != null) {
            List<Traces.Session> active = ss.stream().filter(s -> s.calls().stream().anyMatch(this::sinceSevli)).toList();
            out.append("Since sevli was first used (").append(firstSevli.toString(), 0, 10).append("): ").append(active.size())
                    .append(" sessions were active, ").append(active.stream().filter(s -> s.calls().stream().anyMatch(c -> c.sevli() && sinceSevli(c))).count())
                    .append(" of them used sevli.\n");
        }
        out.append("Read in place from the tools' session files; nothing is stored. Tokens are estimated from output size (4 chars each).\n");

        discovery(out, ss);
        afterSevli(out, ss);
        sevliAnswers(out, ss);
        slowCalls(out);
        investigations(out, ss);
        folders(out, ss);
        return out.toString();
    }

    private boolean sinceSevli(Traces.Call c) {
        return firstSevli != null && c.ts() != null && !c.ts().isBefore(firstSevli);
    }

    /** Shell work sevli could have done, by category. */
    private void discovery(StringBuilder out, List<Traces.Session> ss) throws SQLException {
        out.append("\n## 1. Shell work sevli could have answered\n");
        record Hit(Traces.Session s, Traces.Call c, Category cat) {}
        List<Hit> hits = new ArrayList<>();
        for (Traces.Session s : ss) for (Traces.Call c : s.calls()) {
            Category cat = c.shell() ? classify(c.text()) : null;
            if (cat != null) hits.add(new Hit(s, c, cat));
        }
        if (hits.isEmpty()) {
            out.append("None.\n");
            return;
        }
        Map<Category, List<Hit>> by = hits.stream().collect(Collectors.groupingBy(Hit::cat, LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<Category, List<Hit>>> ordered = new ArrayList<>(by.entrySet());
        ordered.sort(Comparator.comparingLong((Map.Entry<Category, List<Hit>> e) -> e.getValue().stream().mapToLong(h -> h.c().outChars()).sum()).reversed());
        for (var e : ordered) {
            List<Hit> hs = e.getValue();
            long sessions = hs.stream().map(h -> h.s().id()).distinct().count();
            long inSevliSessions = hs.stream().map(Hit::s).distinct().filter(s -> s.calls().stream().anyMatch(Traces.Call::sevli)).count();
            long tokens = hs.stream().mapToLong(h -> h.c().outChars()).sum() / 4;
            long after = hs.stream().filter(h -> sinceSevli(h.c())).count();
            out.append(String.format(Locale.ROOT, "- %s: %d commands in %d sessions (%d of them also used sevli; %d commands since sevli was first used), ~%s tokens of output. sevli: %s%n",
                    e.getKey().name(), hs.size(), sessions, inSevliSessions, after, k(tokens), e.getKey().sevliWay()));
            List<String> anchors = top(hs.stream().flatMap(h -> anchors(h.c().text()).stream()).toList(), 6);
            if (!anchors.isEmpty()) out.append("    about: ").append(String.join(", ", anchors)).append('\n');
            hs.stream().sorted(Comparator.comparing((Hit h) -> !sinceSevli(h.c())).thenComparing(Comparator.comparingInt((Hit h) -> h.c().outChars()).reversed())).limit(2)
                    .forEach(h -> out.append("    e.g. ").append(where(h.s(), h.c())).append(": ").append(clip(h.c().text())).append('\n'));
        }
    }

    /** Shell work right after an sevli answer: the answer did not settle the question. */
    private void afterSevli(StringBuilder out, List<Traces.Session> ss) {
        out.append("\n## 2. Shell work right after an sevli answer (within 3 calls)\n");
        Map<String, Integer> pairs = new LinkedHashMap<>();
        Map<String, String> example = new HashMap<>();
        for (Traces.Session s : ss) {
            List<Traces.Call> cs = s.calls();
            for (int i = 0; i < cs.size(); i++) {
                if (!cs.get(i).sevli()) continue;
                for (int j = i + 1; j < Math.min(cs.size(), i + 4); j++) {
                    Category cat = cs.get(j).shell() ? classify(cs.get(j).text()) : null;
                    if (cat == null) continue;
                    String key = cs.get(i).name() + " -> " + cat.name();
                    pairs.merge(key, 1, Integer::sum);
                    example.putIfAbsent(key, where(s, cs.get(i)) + ": sevli " + cs.get(i).name() + " " + clip(cs.get(i).text(), 90) + "  ->  " + clip(cs.get(j).text(), 90));
                    break;
                }
            }
        }
        if (pairs.isEmpty()) {
            out.append("None.\n");
            return;
        }
        pairs.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8).forEach(e ->
                out.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n    e.g. ").append(example.get(e.getKey())).append('\n'));
    }

    /** sevli answers that cost a follow-up: empty, capped then asked again, errors, and repeats. */
    private void sevliAnswers(StringBuilder out, List<Traces.Session> ss) {
        out.append("\n## 3. sevli answers that cost a follow-up\n");
        record Flag(Traces.Session s, Traces.Call c) {}
        Map<String, List<Flag>> empty = new LinkedHashMap<>(), capped = new LinkedHashMap<>(), errors = new LinkedHashMap<>(), repeats = new LinkedHashMap<>();
        for (Traces.Session s : ss) {
            Set<String> seen = new HashSet<>();
            List<Traces.Call> cs = s.calls();
            for (int i = 0; i < cs.size(); i++) {
                Traces.Call c = cs.get(i);
                if (!c.sevli()) continue;
                if (c.empty()) empty.computeIfAbsent(c.name(), x -> new ArrayList<>()).add(new Flag(s, c));
                if (c.error()) errors.computeIfAbsent(c.name(), x -> new ArrayList<>()).add(new Flag(s, c));
                if (c.capped() && i + 1 < cs.size() && cs.get(i + 1).sevli() && cs.get(i + 1).name().equals(c.name())) {
                    capped.computeIfAbsent(c.name(), x -> new ArrayList<>()).add(new Flag(s, c));
                }
                if (!seen.add(c.name() + " " + c.text())) repeats.computeIfAbsent(c.name(), x -> new ArrayList<>()).add(new Flag(s, c));
            }
        }
        section(out, "empty answers", empty, Flag::c, Flag::s);
        section(out, "capped, then the same tool again", capped, Flag::c, Flag::s);
        section(out, "errors", errors, Flag::c, Flag::s);
        section(out, "the same call twice in one session", repeats, Flag::c, Flag::s);
    }

    private <F> void section(StringBuilder out, String title, Map<String, List<F>> by, Function<F, Traces.Call> call, Function<F, Traces.Session> session) {
        int total = by.values().stream().mapToInt(List::size).sum();
        out.append("- ").append(title).append(": ").append(total);
        if (total == 0) {
            out.append('\n');
            return;
        }
        out.append(" (").append(by.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, List<F>> e) -> e.getValue().size()).reversed())
                .map(e -> e.getKey() + " " + e.getValue().size()).collect(Collectors.joining(", "))).append(")\n");
        by.values().stream().flatMap(List::stream).limit(3).forEach(f ->
                out.append("    e.g. ").append(where(session.apply(f), call.apply(f))).append(": ").append(call.apply(f).name()).append(' ')
                        .append(clip(call.apply(f).text(), 120)).append('\n'));
    }

    /** Slow sevli calls, from sevli's own call log (it records the time of every call; benchmark calls excluded). */
    private void slowCalls(StringBuilder out) {
        out.append("\n## 4. Slow sevli calls (5 s or more, from sevli's call log)\n");
        Map<String, List<long[]>> byTool = new LinkedHashMap<>();
        Map<String, String> worst = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        try (Stream<Path> logs = Files.list(config.home().resolve("logs"))) {
            for (Path f : logs.filter(p -> p.getFileName().toString().matches("calls-\\d{4}-\\d{2}\\.jsonl")).sorted().toList()) {
                try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    for (String line; (line = in.readLine()) != null; ) {
                        JsonObject d;
                        try {
                            d = JsonParser.parseString(line).getAsJsonObject();
                        } catch (RuntimeException e) {
                            continue;
                        }
                        if ("bench".equals(d.has("via") ? d.get("via").getAsString() : null) || d.has("run")) continue;
                        Instant ts = Instant.parse(d.get("ts").getAsString());
                        if (ts.isBefore(since)) continue;
                        String tool = d.get("tool").getAsString();
                        if (tool.equals("grep") && d.getAsJsonObject("args").has("scope")
                                && List.of(d.getAsJsonObject("args").get("scope").getAsString().replace(" ", "").split(",")).contains("source")) tool = "grep scope=source";
                        totals.merge(tool, 1, Integer::sum);
                        long ms = d.get("ms").getAsLong();
                        if (ms < 5000) continue;
                        byTool.computeIfAbsent(tool, x -> new ArrayList<>()).add(new long[]{ms});
                        String args = d.getAsJsonObject("args").toString();
                        if (!worst.containsKey(tool) || ms > Long.parseLong(worst.get(tool).split(" ", 2)[0])) worst.put(tool, ms + " " + clip(args, 120) + (d.has("v") ? " (sevli " + d.get("v").getAsString() + ")" : ""));
                    }
                }
            }
        } catch (IOException e) {
            out.append("No call log.\n");
            return;
        }
        if (byTool.isEmpty()) {
            out.append("None.\n");
            return;
        }
        byTool.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, List<long[]>> e) -> e.getValue().size()).reversed()).forEach(e -> {
            long[] ms = e.getValue().stream().mapToLong(x -> x[0]).sorted().toArray();
            String[] w = worst.get(e.getKey()).split(" ", 2);
            out.append(String.format(Locale.ROOT, "- %s: %d of %d calls, median %.1f s, slowest %.1f s: %s%n", e.getKey(), ms.length,
                    totals.get(e.getKey()), ms[ms.length / 2] / 1000.0, Long.parseLong(w[0]) / 1000.0, w[1]));
        });
    }

    /** The sessions that spent most on shell discovery: candidates for engine fixes or, later, notes. */
    private void investigations(StringBuilder out, List<Traces.Session> ss) throws SQLException {
        out.append("\n## 5. Longest investigations (most shell discovery in one session)\n");
        record Cost(Traces.Session s, int n, long chars, List<String> anchors) {}
        List<Cost> costs = new ArrayList<>();
        for (Traces.Session s : ss) {
            List<Traces.Call> d = s.calls().stream().filter(c -> c.shell() && classify(c.text()) != null).toList();
            if (d.size() < 5) continue;
            List<String> a = new ArrayList<>();
            for (Traces.Call c : d) a.addAll(anchors(c.text()));
            costs.add(new Cost(s, d.size(), d.stream().mapToLong(Traces.Call::outChars).sum(), top(a, 5)));
        }
        if (costs.isEmpty()) {
            out.append("None.\n");
            return;
        }
        costs.stream().sorted(Comparator.comparingLong(Cost::chars).reversed()).limit(5).forEach(c -> out.append(String.format(Locale.ROOT,
                "- %s: %d discovery commands, ~%s tokens, sevli calls %d%s%n", where(c.s(), null), c.n(), k(c.chars() / 4),
                c.s().calls().stream().filter(Traces.Call::sevli).count(), c.anchors().isEmpty() ? "" : "; about " + String.join(", ", c.anchors()))));
    }

    /**
     * Folders where agents did this work without calling sevli at all, since sevli was first used, and whether the folder
     * has sevli's instruction block: agents mostly use sevli where AGENTS.md / CLAUDE.md tells them to.
     */
    private void folders(StringBuilder out, List<Traces.Session> ss) {
        out.append("\n## 6. Folders where agents did this work without sevli\n");
        Map<String, long[]> by = new LinkedHashMap<>(); // folder -> {sessions, discovery commands}
        for (Traces.Session s : ss) {
            if (s.cwd() == null || s.calls().stream().anyMatch(Traces.Call::sevli)) continue;
            long n = s.calls().stream().filter(c -> c.shell() && (firstSevli == null || sinceSevli(c))).map(c -> classify(c.text()))
                    .filter(c -> c != null && !c.name().startsWith("Forge")).count();
            if (n == 0) continue;
            long[] v = by.computeIfAbsent(s.cwd(), k -> new long[2]);
            v[0]++;
            v[1] += n;
        }
        if (by.isEmpty()) {
            out.append("None.\n");
            return;
        }
        by.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed()).limit(8).forEach(e -> {
            boolean block = hasInstructions(Path.of(e.getKey()));
            out.append("- ").append(clip(e.getKey(), 100)).append(": ").append(e.getValue()[1]).append(" commands in ").append(e.getValue()[0])
                    .append(" session(s); ").append(block ? "has sevli instructions" : "no sevli instructions: sevli setup --project \"" + clip(e.getKey(), 100) + "\"")
                    .append('\n');
        });
    }

    /** True if the folder or a parent has sevli's instruction block in AGENTS.md or CLAUDE.md. */
    static boolean hasInstructions(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            for (String name : List.of("AGENTS.md", "CLAUDE.md")) {
                Path f = d.resolve(name);
                try {
                    if (Files.isRegularFile(f) && (Files.readString(f).contains("sevli:begin") || Files.readString(f).contains("envx:begin"))) return true;
                } catch (IOException | RuntimeException e) {
                    // unreadable: keep looking
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ anchors and formatting

    /** What a command is about: jar names (without versions) and words that are class names in the index. */
    List<String> anchors(String cmd) {
        if (cmd == null) return List.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        Matcher j = JAR.matcher(cmd);
        while (j.find()) if (!j.group(1).equalsIgnoreCase("sevli") && !j.group(1).equalsIgnoreCase("envx")) out.add(j.group(1) + ".jar");
        Matcher w = WORD.matcher(SHELL_NOISE.matcher(cmd).replaceAll(" "));
        while (w.find()) if (isClass(w.group())) out.add(w.group());
        return new ArrayList<>(out);
    }

    private boolean isClass(String simple) {
        if (db == null) return false;
        Boolean known = classCache.get(simple);
        if (known == null) {
            try {
                known = db.queryInt("SELECT count(*) FROM (SELECT 1 FROM class WHERE simple=? LIMIT 1)", simple.toLowerCase(Locale.ROOT)) > 0
                        || db.queryInt("SELECT count(*) FROM (SELECT 1 FROM class WHERE named LIKE ? LIMIT 1)", "%/" + simple) > 0;
            } catch (SQLException e) {
                known = false;
            }
            classCache.put(simple, known);
        }
        return known;
    }

    private static List<String> top(List<String> items, int n) {
        Map<String, Long> counts = items.stream().collect(Collectors.groupingBy(x -> x, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(n)
                .map(e -> e.getKey() + (e.getValue() > 1 ? " (" + e.getValue() + ")" : "")).toList();
    }

    private static long count(List<Traces.Session> ss, java.util.function.Predicate<Traces.Call> p) {
        return ss.stream().flatMap(s -> s.calls().stream()).filter(p).count();
    }

    /** Provenance: tool, date, project folder, short session id. */
    static String where(Traces.Session s, Traces.Call c) {
        Instant t = c != null && c.ts() != null ? c.ts() : s.start();
        String project = s.cwd() == null ? "?" : Path.of(s.cwd()).getFileName() == null ? s.cwd() : Path.of(s.cwd()).getFileName().toString();
        return "[" + s.tool() + " " + (t == null ? "?" : t.toString().substring(0, 10)) + " " + project + " " + (s.id() == null ? "?" : s.id().substring(0, Math.min(8, s.id().length()))) + "]";
    }

    static String clip(String text) {
        return clip(text, 140);
    }

    /** One line, secrets redacted, the home folder shortened to ~, at most {@code max} chars. */
    static String clip(String text, int max) {
        if (text == null) return "";
        String home = System.getProperty("user.home");
        String t = SecretFilter.redact(text.replace(home, "~").replace(home.replace('\\', '/'), "~")).replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String k(long n) {
        return n >= 10_000 ? (n / 1000) + "k" : String.valueOf(n);
    }
}
