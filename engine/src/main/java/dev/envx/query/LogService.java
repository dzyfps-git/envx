package dev.envx.query;

import dev.envx.env.LogMirror;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import dev.envx.env.SecretFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * {@code env filter=errors[:text]}: the live server's recent warnings, errors, failed mixins and crashes, grouped by
 * signature (message with numbers normalized, plus the first frame that is not library code), with counts, first and
 * last time, and stack frames resolved to Yarn names and the mod that owns them. Replaces agents reading megabytes of
 * logs line by line.
 */
public final class LogService {
    private static final Pattern HEADER = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] \\[([^\\]]+)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]: ?(.*)$");
    /** A stack frame, as printed by Throwable ("\tat x.y(Z.java:1)") or logged line by line ("knot//x.y(Z.java:1)"). */
    private static final Pattern FRAME = Pattern.compile("^\\s*(?:at\\s+)?([\\w$.@/+-]+)\\.([\\w$<>-]+)\\(([^)]*)\\)(?:\\s.*)?$");
    private static final Pattern HANDLER = Pattern.compile(
            "(?:handler|redirect|wrapOperation|wrapMethod|inject|localvar|cancellable|modify[A-Za-z]*)\\$[a-z0-9]+\\$([A-Za-z0-9_.-]+?)\\$(.+)$");
    private static final Pattern MIXIN_FAILED = Pattern.compile("Mixin apply for mod (\\S+) failed (\\S+) from mod \\S+ -> (\\S+?):? ");
    private static final Pattern LIBRARY = Pattern.compile(
            "^(java|javax|jdk|sun|com\\.sun|kotlin|scala|it\\.unimi|com\\.google|org\\.apache|org\\.slf4j|io\\.netty|org\\.spongepowered"
                    + "|com\\.llamalad7|org\\.objectweb|net\\.fabricmc\\.loader|com\\.mojang\\.(brigadier|serialization|datafixers)|org\\.joml)\\.");
    private static final Pattern NUMBER = Pattern.compile("-?\\b\\d+(?:\\.\\d+)?[fdL]?\\b");
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** Resource ids (copycats:recipes/x) vary per occurrence of one problem; the namespace is what identifies it. */
    private static final Pattern RESOURCE_ID = Pattern.compile("\\b([a-z0-9_.-]+):[a-z0-9_./-]*[a-z0-9_]\\b");
    private static final Pattern HASH = Pattern.compile("@[0-9a-f]{5,}\\b");
    /** Kinds listed per level; the rest are counted (filter=errors:<text> narrows). */
    private static final int KINDS_SHOWN = 12;
    /** A top-level entry of a crash report's mod list: two tabs, "id: Name version". */
    private static final Pattern CRASH_MOD = Pattern.compile("^\\t\\t([a-z0-9_.-]+): .* (\\S+)$");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** One logged warning/error (or a crash report) with its trace. */
    record Event(LocalDateTime time, String level, String message, String exception, String cause, List<String[]> frames,
                 Map<String, String> versions) {}

    /** Occurrences of one signature: counts and times, one sample (the latest), and how many distinct messages. */
    static final class Group {
        String level;
        int count;
        LocalDateTime first, last;
        Event sample;
        Set<Integer> variants = new HashSet<>();

        void add(Event e) {
            if (level == null) level = e.level();
            count++;
            if (first == null || e.time().isBefore(first)) first = e.time();
            if (last == null || !e.time().isBefore(last)) {
                last = e.time();
                sample = e;
            }
            if (variants.size() < 1000) variants.add(e.message().hashCode());
        }

        void merge(Group g) {
            if (level == null) level = g.level;
            count += g.count;
            if (first == null || g.first.isBefore(first)) first = g.first;
            if (last == null || !g.last.isBefore(last)) {
                last = g.last;
                sample = g.sample;
            }
            if (variants.size() < 1000) variants.addAll(g.variants);
        }
    }

    /** Groups per immutable file (rotated logs, crash reports), in memory and on disk: each is parsed once. */
    private static final Map<String, Map<String, Group>> PARSED = new ConcurrentHashMap<>();
    /** Bump when parsing or signatures change, so cached summaries are rebuilt. */
    private static final String CACHE_VERSION = "v3";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (v, t, c) -> new JsonPrimitive(v.toString()))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (j, t, c) -> LocalDateTime.parse(j.getAsString()))
            .create();

    private final QueryService q;

    LogService(QueryService q) {
        this.q = q;
    }

    String errors(Scope s, String text, int budget) throws SQLException, IOException {
        if (s.historical()) {
            return "Logs are kept for the current server only (the last " + q.config().logDays + " days); drop env=" + s.env() + "@" + s.at() + ".";
        }
        Loaded whole = load(s, null);
        String problem = whole.problem();
        List<Path> logs = whole.logs();
        List<Path> crashFiles = whole.crashFiles();
        if (logs.isEmpty() && crashFiles.isEmpty()) {
            return "No server logs mirrored for " + s.env() + (problem == null ? "" : " (source unreachable: " + problem + ")") + ".";
        }
        Filter filter = Filter.parse(text, whole.to());
        Loaded l = filter.dated() ? load(s, filter) : whole;
        String needle = filter.text() == null ? null : filter.text().toLowerCase(Locale.ROOT);
        Map<String, Group> all = l.all();
        Map<String, Group> crashes = l.crashes();
        LocalDate from = l.from(), to = l.to();

        Frames frames = new Frames(s);
        Out out = new Out(budget);
        Instant checked = LogMirror.checkedAt(q.config().home(), s.env());
        out.force("server logs of " + s.env() + (from == null ? "" : " " + from + " .. " + to) + ": " + logs.size() + " log file(s), "
                + crashFiles.size() + " crash report(s)" + (filter.dated() ? ", " + filter.describe() : "")
                + (needle == null ? "" : ", matching '" + filter.text() + "'")
                + (problem == null ? "" : "; source unreachable (" + problem + "), copy from " + (checked == null ? "?" : age(checked))));

        // Crashes first, newest first; identical crashes are one entry.
        List<Group> crashList = crashes.values().stream().filter(g -> needle == null || matches(g.sample, needle, frames))
                .sorted(Comparator.comparing((Group g) -> g.last).reversed()).toList();
        if (!crashList.isEmpty()) out.line("crashes (" + crashList.stream().mapToInt(g -> g.count).sum() + "):");
        for (Group g : crashList) {
            Event c = g.sample;
            if (out.line("  " + when(g) + "  " + c.message() + ": " + clip(SecretFilter.redact(c.cause() != null ? c.cause() : c.exception()), 160))) {
                String blame = frames.blame(c);
                if (!blame.isEmpty()) out.line("    " + blame);
            }
        }

        // Mixins that failed to apply: once per mixin, however many restarts logged it.
        List<Group> failed = all.entrySet().stream().filter(e -> e.getKey().startsWith("MIXIN|")).map(Map.Entry::getValue)
                .filter(g -> needle == null || matches(g.sample, needle, frames)).toList();
        if (!failed.isEmpty()) out.line("mixins that failed to apply (" + failed.size() + "; declared in the index, not applied at runtime):");
        for (Group g : failed) {
            Event e = g.sample;
            Matcher m = MIXIN_FAILED.matcher(e.message());
            if (!m.find()) continue;
            String why = e.exception() == null ? "" : e.exception().replaceFirst("^[\\w.]+\\.(\\w+Exception|\\w+Error): ", "$1: ");
            out.line("  " + frames.mod(m.group(1)) + ": " + m.group(2).replaceFirst("^[^:]+:", "") + " -> " + frames.className(m.group(3))
                    + "  x" + g.count + " (last " + g.last.format(SHORT) + ")  " + clip(SecretFilter.redact(why), 200));
        }

        LocalDateTime changed = LocalDateTime.ofInstant(Instant.parse(s.takenAt()), ZoneId.systemDefault());
        boolean changedInWindow = from != null && from.atStartOfDay().isBefore(changed);
        boolean any = !crashList.isEmpty() || !failed.isEmpty();
        for (String level : List.of("ERROR", "WARN")) {
            List<Group> ofLevel = all.entrySet().stream().filter(e -> !e.getKey().startsWith("MIXIN|")).map(Map.Entry::getValue)
                    .filter(g -> level.equals(g.level) || level.equals("ERROR") && "FATAL".equals(g.level))
                    .filter(g -> needle == null || matches(g.sample, needle, frames))
                    .sorted(Comparator.comparingInt((Group g) -> g.count).reversed()).toList();
            if (ofLevel.isEmpty()) continue;
            any = true;
            // Errors get more room than warnings; a filter asks about something specific, so it shows more kinds.
            int cap = needle != null ? 40 : level.equals("ERROR") ? KINDS_SHOWN : KINDS_SHOWN / 2;
            out.line((level.equals("ERROR") ? "errors" : "warnings") + " (" + ofLevel.size() + " kinds, " + ofLevel.stream().mapToInt(g -> g.count).sum()
                    + " lines; most frequent first" + (ofLevel.size() > cap ? ", top " + cap : "") + "):");
            for (Group g : ofLevel.subList(0, Math.min(cap, ofLevel.size()))) {
                Event e = g.sample;
                String exc = e.exception() == null ? "" : " | " + (e.cause() != null ? e.cause() : e.exception());
                boolean shown = out.line("  x" + g.count + "  " + when(g) + (changedInWindow && g.first.isAfter(changed) ? "  NEW since this pack version" : "")
                        + "  " + clip(SecretFilter.redact(e.message() + exc), 200) + (g.variants.size() > 1 ? "  (" + g.variants.size() + " variants)" : ""));
                String blame = shown ? frames.blame(e) : "";
                if (!blame.isEmpty()) out.line("    " + blame + (g.last.isBefore(changed) ? "  (before the current pack version; mods may have been older)" : ""));
            }
            if (ofLevel.size() > cap) {
                out.line("  +" + (ofLevel.size() - cap) + " rarer kinds (" + ofLevel.subList(cap, ofLevel.size()).stream().mapToInt(g -> g.count).sum() + " lines)");
            }
        }
        if (!any && (needle != null || filter.dated())) {
            // An empty filtered answer says what the filter left out, so the next call is not a blind grep.
            long kinds = whole.all().keySet().stream().filter(k -> !k.startsWith("MIXIN|")).count();
            out.line("nothing matches" + (filter.dated() ? " " + filter.describe() : "") + (needle == null ? "" : " '" + filter.text() + "'")
                    + "; without the filter: " + whole.crashes().values().stream().mapToInt(g -> g.count).sum() + " crash(es), "
                    + kinds + " error/warning kind(s) (env filter=errors). Text matches messages, classes and mod ids; "
                    + "a date (2026-09-10, 09-10 or 09-01..09-10) selects by time.");
        } else if (all.isEmpty() && crashList.isEmpty()) {
            out.line("no warnings or errors");
        }
        return out.finish("narrow with filter=errors:<text> (a mod id, class or message) or a date (09-10, 09-01..09-10); "
                + "grep scope=logs shows the lines around one");
    }

    /** {@code errors:<spec>}: an optional date or date range (2026-09-10, 09-10, A..B, A.., ..B) and optional text. */
    record Filter(LocalDate from, LocalDate to, boolean dated, String text) {
        private static final Pattern RANGE = Pattern.compile("^((?:\\d{4}-)?\\d\\d-\\d\\d)?(\\.\\.((?:\\d{4}-)?\\d\\d-\\d\\d)?)?$");

        static Filter parse(String spec, LocalDate latest) {
            if (spec == null || spec.isBlank()) return new Filter(null, null, false, null);
            LocalDate ref = latest != null ? latest : LocalDate.now();
            List<String> rest = new ArrayList<>();
            LocalDate from = null, to = null;
            boolean dated = false;
            for (String tok : spec.trim().split("\\s+")) {
                Matcher m = RANGE.matcher(tok);
                if (!dated && m.matches() && (m.group(1) != null || m.group(3) != null)) {
                    try {
                        from = m.group(1) == null ? null : date(m.group(1), ref);
                        to = m.group(2) == null ? from : m.group(3) == null ? null : date(m.group(3), ref);
                        dated = true;
                        continue;
                    } catch (java.time.DateTimeException notADate) {
                        from = to = null;
                    }
                }
                rest.add(tok);
            }
            return new Filter(from, to, dated, rest.isEmpty() ? null : String.join(" ", rest));
        }

        /** A month-day means the latest such day on or before the newest log. */
        private static LocalDate date(String d, LocalDate ref) {
            if (d.length() == 10) return LocalDate.parse(d);
            LocalDate x = LocalDate.parse(ref.getYear() + "-" + d);
            return x.isAfter(ref) ? x.minusYears(1) : x;
        }

        boolean covers(Group g) {
            return (from == null || !g.last.toLocalDate().isBefore(from)) && (to == null || !g.first.toLocalDate().isAfter(to));
        }

        String describe() {
            return from != null && from.equals(to) ? "on " + from : (from == null ? "up to " + to : to == null ? "from " + from : from + " .. " + to);
        }
    }

    private record Loaded(List<Path> logs, List<Path> crashFiles, Map<String, Group> all, Map<String, Group> crashes,
                          LocalDate from, LocalDate to, String problem) {}

    private Loaded load(Scope s) throws IOException {
        return load(s, null);
    }

    /**
     * The mirror, refreshed when older than 2 minutes, summarized: log groups by signature and crash groups. With a
     * dated filter, only each file's groups that occurred in its range are merged (times are per file, so a signature
     * counts only its occurrences from files within the range).
     */
    private Loaded load(Scope s, Filter range) throws IOException {
        String problem = LogMirror.refresh(q.config(), s.env(), LogMirror.QUERY_MAX_AGE);
        Path root = LogMirror.dir(q.config().home(), s.env());
        List<Path> logs = files(root.resolve("logs"));
        List<Path> crashFiles = files(root.resolve("crash-reports"));
        Map<String, Group> all = new LinkedHashMap<>();
        LocalDate from = null, to = null;
        for (Path f : logs) {
            summary(root, f, true).forEach((k, g) -> {
                if (range == null || range.covers(g)) all.computeIfAbsent(k, x -> new Group()).merge(g);
            });
            LocalDate d = fileDate(f);
            if (from == null || d.isBefore(from)) from = d;
            if (to == null || d.isAfter(to)) to = d;
        }
        Map<String, Group> crashes = new LinkedHashMap<>();
        for (Path f : crashFiles) {
            summary(root, f, false).forEach((k, g) -> {
                if (range == null || range.covers(g)) crashes.computeIfAbsent(k, x -> new Group()).merge(g);
            });
        }
        if (range == null) prune(root, logs, crashFiles);
        return new Loaded(logs, crashFiles, all, crashes, from, to, problem);
    }

    /**
     * What the current server's logs show since {@code since} (a pack update): crashes, and error kinds, warning kinds
     * and failed mixins first seen after it, with the error kinds involving {@code modId} (may be null) listed.
     */
    List<String> since(Scope s, LocalDateTime since, String modId) throws IOException, SQLException {
        Loaded l = load(s);
        if (l.logs().isEmpty() && l.crashFiles().isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        String head = "server logs since " + since.format(SHORT);
        if (l.from() != null && l.from().atStartOfDay().isAfter(since)) head += " (logs only go back to " + l.from() + ")";
        Frames frames = new Frames(s);
        String needle = modId == null ? null : modId.toLowerCase(Locale.ROOT);
        int crashes = 0;
        List<Group> mineCrashes = new ArrayList<>();
        for (Group g : l.crashes().values()) {
            if (!g.last.isAfter(since)) continue;
            crashes += g.count;
            if (needle != null && matches(g.sample, needle, frames)) mineCrashes.add(g);
        }
        int errors = 0, warnings = 0, mixins = 0;
        List<Group> mine = new ArrayList<>();
        for (var e : l.all().entrySet()) {
            Group g = e.getValue();
            if (!g.first.isAfter(since)) continue;
            if (e.getKey().startsWith("MIXIN|")) mixins++;
            else if ("WARN".equals(g.level)) warnings++;
            else errors++;
            if (needle != null && !"WARN".equals(g.level) && matches(g.sample, needle, frames)) mine.add(g);
        }
        out.add(head + ": " + crashes + " crash(es); new: " + errors + " error kind(s), " + warnings + " warning kind(s), "
                + mixins + " failed mixin(s)" + (needle == null ? "" : "; involving " + modId + ": " + mineCrashes.size() + " crash kind(s), "
                + mine.size() + " error kind(s)") + " (env filter=errors" + (mine.isEmpty() && mineCrashes.isEmpty() ? "" : ":" + modId) + ")");
        List<Group> shown = new ArrayList<>(mineCrashes);
        mine.stream().sorted(Comparator.comparingInt((Group g) -> g.count).reversed()).limit(3).forEach(shown::add);
        for (Group g : shown) {
            Event e = g.sample;
            String exc = e.exception() == null ? "" : " | " + (e.cause() != null ? e.cause() : e.exception());
            out.add("  x" + g.count + "  " + when(g) + "  " + clip(SecretFilter.redact(e.message() + exc), 160));
        }
        return out;
    }

    /**
     * Mixins the log mirror says failed to apply, as {@code config:relative.Class} -> last time. Uses the mirror as it
     * is (no refresh); rotated logs come from their cached summaries, so this is cheap after the first summary.
     */
    public static Map<String, LocalDateTime> failedMixins(Path home, String env) {
        Map<String, LocalDateTime> out = new HashMap<>();
        Path root = LogMirror.dir(home, env);
        try {
            for (Path f : files(root.resolve("logs"))) {
                for (var e : summary(root, f, true).entrySet()) {
                    if (!e.getKey().startsWith("MIXIN|")) continue;
                    String k = e.getKey().substring(e.getKey().indexOf(' ') + 1);
                    out.merge(k, e.getValue().last, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no evidence is not an error
        }
        return out;
    }

    /** One line for the env summary: recent crashes from the mirror as it is, and how to ask for more. */
    static String summaryLine(Path home, String env) {
        Path crashes = LogMirror.dir(home, env).resolve("crash-reports");
        try {
            long weekAgo = Instant.now().minus(java.time.Duration.ofDays(7)).toEpochMilli();
            List<Path> recent = new ArrayList<>();
            for (Path f : files(crashes)) if (Files.getLastModifiedTime(f).toMillis() >= weekAgo) recent.add(f);
            String crashText = recent.isEmpty() ? "no crash reports in 7 days" : recent.size() + " crash report(s) in 7 days, newest "
                    + recent.getLast().getFileName().toString().replaceAll("crash-(\\d{4}-\\d\\d-\\d\\d)_(\\d\\d)\\.(\\d\\d).*", "$1 $2:$3");
            return "server logs: " + (LogMirror.checkedAt(home, env) == null ? "not copied yet" : crashText)
                    + "; env filter=errors[:text] summarizes errors, failed mixins and crashes";
        } catch (IOException e) {
            return null;
        }
    }

    /** Summarizes new mirrored files ahead of time (sync does this, so the first query is fast). */
    public static void warm(Path home, String env) {
        Path root = LogMirror.dir(home, env);
        try {
            for (Path f : files(root.resolve("logs"))) summary(root, f, true);
            for (Path f : files(root.resolve("crash-reports"))) summary(root, f, false);
        } catch (IOException | RuntimeException ignored) {
            // a cache
        }
    }

    private static String when(Group g) {
        return g.first.format(SHORT) + (g.count > 1 ? " .. " + g.last.format(SHORT) : "");
    }

    // ------------------------------------------------------------------ parsing

    static final String STACK_ONLY = "(stack trace logged line by line)";

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> {
                String n = f.getFileName().toString();
                return n.endsWith(".log") || n.endsWith(".log.gz") || n.endsWith(".txt");
            }).sorted().toList();
        }
    }

    /** Signature groups of one file; rotated logs and crash reports are summarized once and cached on disk. */
    private static Map<String, Group> summary(Path root, Path f, boolean log) throws IOException {
        String name = f.getFileName().toString();
        boolean immutable = !name.equals("latest.log");
        Path cache = root.resolve(".parsed").resolve(CACHE_VERSION + "-" + name + "-" + Files.size(f) + ".json");
        String key = cache.toString();
        if (immutable) {
            Map<String, Group> hit = PARSED.get(key);
            if (hit != null) return hit;
            if (Files.exists(cache)) {
                try {
                    Map<String, Group> m = GSON.fromJson(Files.readString(cache), new TypeToken<LinkedHashMap<String, Group>>() {}.getType());
                    PARSED.put(key, m);
                    return m;
                } catch (RuntimeException corrupt) {
                    // rebuilt below
                }
            }
        }
        List<Event> events = log ? parseLog(read(f), fileDate(f)) : parseCrash(read(f), name);
        Map<String, Group> m = new LinkedHashMap<>();
        for (Event e : events) {
            Matcher mf = MIXIN_FAILED.matcher(e.message());
            String k = mf.find() ? "MIXIN|" + mf.group(1) + " " + mf.group(2) : signature(e);
            m.computeIfAbsent(k, x -> new Group()).add(e);
        }
        if (immutable) {
            PARSED.put(key, m);
            try {
                Files.createDirectories(cache.getParent());
                Path tmp = cache.resolveSibling(cache.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
                Files.writeString(tmp, GSON.toJson(m));
                Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                // a cache; the next call parses again
            }
        }
        return m;
    }

    /** Drops cached summaries of files that left the mirror. */
    private static void prune(Path root, List<Path> logs, List<Path> crashes) {
        Path dir = root.resolve(".parsed");
        if (!Files.isDirectory(dir)) return;
        Set<String> live = new HashSet<>();
        for (Path f : logs) live.add(f.getFileName().toString());
        for (Path f : crashes) live.add(f.getFileName().toString());
        try (Stream<Path> s = Files.list(dir)) {
            for (Path c : s.toList()) {
                String n = c.getFileName().toString();
                Matcher m = Pattern.compile("^(v\\d+)-(.+)-\\d+\\.json$").matcher(n);
                if (!m.matches() || !m.group(1).equals(CACHE_VERSION) || !live.contains(m.group(2))) Files.deleteIfExists(c);
            }
        } catch (IOException ignored) {
            // a cache
        }
    }

    static List<String> read(Path f) throws IOException {
        try (InputStream in = f.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** logs/2026-09-23-2.log.gz holds that day; latest.log holds the day it was last written (logs roll at midnight). */
    static LocalDate fileDate(Path f) {
        String n = f.getFileName().toString();
        if (n.matches("\\d{4}-\\d\\d-\\d\\d-.*")) return LocalDate.parse(n.substring(0, 10));
        try {
            return LocalDate.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDate.now();
        }
    }

    /** WARN/ERROR/FATAL records with their continuation lines; a trace logged one frame per line joins its record. */
    static List<Event> parseLog(List<String> lines, LocalDate day) {
        List<Event> out = new ArrayList<>();
        Builder cur = null;
        for (String line : lines) {
            Matcher h = HEADER.matcher(line);
            if (h.matches()) {
                String level = h.group(3);
                String msg = h.group(4);
                boolean frameLine = FRAME.matcher(msg).matches();
                if (frameLine && cur != null && cur.thread.equals(h.group(2)) && cur.clock.equals(h.group(1)) && cur.frameLines) {
                    cur.frame(msg);
                    continue;
                }
                if (cur != null) out.add(cur.build());
                cur = null;
                if (!level.equals("WARN") && !level.equals("ERROR") && !level.equals("FATAL")) continue;
                cur = new Builder(day.atTime(java.time.LocalTime.parse(h.group(1))), level, h.group(2), h.group(1), frameLine ? STACK_ONLY : msg);
                if (frameLine) {
                    cur.frameLines = true;
                    cur.frame(msg);
                }
            } else if (cur != null) {
                cur.continuation(line);
            }
        }
        if (cur != null) out.add(cur.build());
        return out;
    }

    static List<Event> parseCrash(List<String> lines, String name) {
        LocalDateTime time = null;
        String description = "crash";
        Builder b = null;
        boolean traceDone = false, mods = false;
        Map<String, String> versions = new HashMap<>(); // the report's own mod list: versions at the time of the crash
        for (String line : lines) {
            if (traceDone) {
                if (line.contains("Fabric Mods:")) {
                    mods = true;
                } else if (mods) {
                    Matcher v = CRASH_MOD.matcher(line);
                    if (v.matches()) versions.put(v.group(1), v.group(2));
                    else if (!line.startsWith("\t\t\t")) mods = false; // nested jars are listed one level deeper
                }
                continue;
            }
            if (line.startsWith("Time: ")) {
                try {
                    time = LocalDateTime.parse(line.substring(6).trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } catch (RuntimeException ignored) {
                    // the file name has the time too
                }
            } else if (line.startsWith("Description: ")) {
                description = line.substring(13).trim();
            } else if (b == null && time != null && !line.isBlank() && !line.startsWith("//") && !line.startsWith("----")) {
                b = new Builder(time, "CRASH", "?", "", "crash: " + description);
                b.continuation(line);
            } else if (b != null) {
                if (line.startsWith("A detailed walkthrough") || line.startsWith("-- ")) traceDone = true;
                else b.continuation(line);
            }
        }
        if (time == null) {
            Matcher m = Pattern.compile("crash-(\\d{4}-\\d\\d-\\d\\d)_(\\d\\d)\\.(\\d\\d)\\.(\\d\\d)").matcher(name);
            time = m.find() ? LocalDateTime.parse(m.group(1) + "T" + m.group(2) + ":" + m.group(3) + ":" + m.group(4)) : LocalDateTime.now();
        }
        if (b == null) return List.of(new Event(time, "CRASH", "crash: " + description, null, null, List.of(), versions));
        b.versions = versions;
        return List.of(b.build());
    }

    private static final class Builder {
        final LocalDateTime time;
        final String level, thread, clock, message;
        String exception, cause;
        boolean frameLines;
        Map<String, String> versions;
        /** Frames of the innermost cause seen so far (the root cause is what to blame). */
        List<String[]> frames = new ArrayList<>();
        final List<String[]> handlers = new ArrayList<>();

        Builder(LocalDateTime time, String level, String thread, String clock, String message) {
            this.time = time;
            this.level = level;
            this.thread = thread;
            this.clock = clock;
            this.message = message;
        }

        void continuation(String line) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("...")) return;
            if (FRAME.matcher(t).matches() && (t.startsWith("at ") || line.startsWith("\t"))) {
                frame(t);
            } else if (t.startsWith("Caused by: ")) {
                cause = t.substring(11);
                frames = new ArrayList<>();
            } else if (exception == null && frames.isEmpty()) {
                exception = t;
            }
        }

        void frame(String text) {
            Matcher m = FRAME.matcher(text.strip());
            if (!m.matches()) return;
            String cls = m.group(1);
            cls = cls.substring(cls.lastIndexOf('/') + 1); // knot//MC//, java.base@17/ prefixes
            String[] f = {cls, m.group(2), m.group(3)};
            if (HANDLER.matcher(f[1]).find()) handlers.add(f);
            if (frames.size() < 40) frames.add(f);
        }

        Event build() {
            List<String[]> all = new ArrayList<>(frames);
            for (String[] h : handlers) if (!all.contains(h)) all.add(h); // handlers above the root cause still name mods
            return new Event(time, level, clip(message, 400), exception == null ? null : clip(exception, 300),
                    cause == null ? null : clip(cause, 300), List.copyOf(all), versions);
        }
    }

    /** Level, normalized message and exception type, and the first mod frame (else the first non-library one). */
    static String signature(Event e) {
        String first = e.frames().stream().filter(f -> !LIBRARY.matcher(f[0] + ".").lookingAt())
                .filter(f -> !f[0].startsWith("net.minecraft.") || HANDLER.matcher(f[1]).find()).findFirst()
                .or(() -> e.frames().stream().filter(f -> !LIBRARY.matcher(f[0] + ".").lookingAt()).findFirst())
                .map(f -> f[0] + "." + f[1]).orElse("");
        String exc = e.cause() != null ? e.cause() : e.exception() == null ? "" : e.exception();
        return e.level() + "|" + normalize(e.message(), 90) + "|" + normalize(exc.replaceFirst(":.*", ""), 80) + "|" + first;
    }

    static String normalize(String s, int max) {
        String n = UUID.matcher(s).replaceAll("U");
        n = HASH.matcher(n).replaceAll("@H");
        n = RESOURCE_ID.matcher(n).replaceAll("$1:*");
        n = NUMBER.matcher(n).replaceAll("N");
        return n.length() > max ? n.substring(0, max) : n;
    }

    private static boolean matches(Event e, String needle, Frames frames) {
        if ((e.message() + " " + e.exception() + " " + e.cause()).toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (String[] f : e.frames()) if ((f[0] + "." + f[1]).toLowerCase(Locale.ROOT).contains(needle)) return true;
        return frames.mods(e).stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static LocalDateTime last(List<Event> g) {
        return g.stream().map(Event::time).max(Comparator.naturalOrder()).orElseThrow();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String age(Instant t) {
        long min = java.time.Duration.between(t, Instant.now()).toMinutes();
        return min < 60 ? min + " min ago" : min < 2880 ? (min / 60) + " h ago" : (min / 1440) + " days ago";
    }

    // ------------------------------------------------------------------ frames -> Yarn names and mods

    /** Resolves class names (runtime or Yarn, dotted) against the scope's artifacts; cached per answer. */
    private final class Frames {
        private final Scope s;
        private final Map<String, String[]> classes = new HashMap<>(); // dotted name -> {class id, yarn simple, owner label}
        private final Map<String, String> modLabels = new HashMap<>();

        Frames(Scope s) {
            this.s = s;
        }

        String[] cls(String dotted) {
            return classes.computeIfAbsent(dotted, d -> {
                String internal = d.replace('.', '/');
                try {
                    List<String[]> rows = q.db().query("SELECT c.id, c.named, c.artifact_id FROM class c WHERE c.name=? AND " + s.artifacts("c") + " LIMIT 1",
                            rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, internal);
                    if (rows.isEmpty()) rows = q.db().query("SELECT c.id, c.named, c.artifact_id FROM class c WHERE c.named=? COLLATE NOCASE AND "
                            + s.artifacts("c") + " LIMIT 1", rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, internal);
                    if (rows.isEmpty()) return null;
                    String[] r = rows.getFirst();
                    return new String[]{r[0], Sig.simple(r[1]), q.label(Long.parseLong(r[2]))};
                } catch (SQLException e) {
                    return null;
                }
            });
        }

        /** Yarn simple name of a runtime or Yarn class name, with the runtime name in brackets when they differ. */
        String className(String dotted) {
            String[] c = cls(dotted);
            if (c == null) return dotted.substring(dotted.lastIndexOf('.') + 1);
            String runtime = dotted.substring(dotted.lastIndexOf('.') + 1);
            return c[1].equals(runtime) ? c[1] : c[1] + " [" + runtime + "]";
        }

        String method(String[] c, String method) {
            Matcher inner = Pattern.compile("method_\\d+").matcher(method);
            String name = inner.find() ? inner.group() : method;
            try {
                String named = q.db().queryString("SELECT named FROM member WHERE class_id=? AND kind='m' AND name=? LIMIT 1", Long.parseLong(c[0]), name);
                return named == null || named.equals(name) ? name : named + " [" + name + "]";
            } catch (SQLException e) {
                return name;
            }
        }

        /** "modid version" for a mod id in this environment, else the id. */
        String mod(String id) {
            return modLabels.computeIfAbsent(id, i -> {
                try {
                    String v = q.db().queryString("SELECT a.mod_version FROM artifact a WHERE a.mod_id=? AND a.id IN (" + s.artifactSet() + ") LIMIT 1", i);
                    return v == null ? i : i + " " + v;
                } catch (SQLException e) {
                    return i;
                }
            });
        }

        /** Owner of a frame: a mixin handler's mod, the class's jar, "minecraft", or null for library code. */
        String owner(String[] f) {
            Matcher h = HANDLER.matcher(f[1]);
            if (h.find()) return mod(h.group(1));
            if (LIBRARY.matcher(f[0] + ".").lookingAt()) return null;
            String[] c = cls(f[0]);
            if (c != null) return c[2].trim();
            return f[0].startsWith("net.minecraft.") ? "minecraft" : null;
        }

        /** The owner as of the event: a crash report lists the versions it ran, which may be older than today's. */
        String owner(Event e, String[] f) {
            String o = owner(f);
            if (o == null || e.versions() == null || o.equals("minecraft")) return o;
            String id = o.split(" ")[0];
            String then = e.versions().get(id);
            if (then == null || o.equals(id + " " + then)) return o;
            String now = o.substring(id.length()).trim();
            return id + " " + then + (now.isEmpty() ? " (not on the server now)" : " (now " + now + ")");
        }

        Set<String> mods(Event e) {
            Set<String> out = new LinkedHashSet<>();
            for (String[] f : e.frames()) {
                String o = owner(e, f);
                if (o != null && !o.equals("minecraft")) out.add(o);
            }
            return out;
        }

        /**
         * Up to three frames worth reading, top of the stack first: mod code and mixin handlers; else the first
         * Minecraft frame. Plus the mods involved.
         */
        String blame(Event e) {
            List<String> shown = new ArrayList<>();
            Set<String> shownMods = new HashSet<>();
            String firstMc = null;
            List<String[]> frames = e.frames();
            int last = -1;
            for (int i = 0; i < frames.size() && shown.size() < 3; i++) {
                String o = owner(e, frames.get(i));
                if (o == null) continue;
                String text = text(frames.get(i), o);
                if (o.equals("minecraft")) {
                    if (firstMc == null) firstMc = text;
                    continue;
                }
                if (!shown.contains(text)) shown.add(text);
                shownMods.add(o.split(" ")[0]);
                last = i;
            }
            if (shown.isEmpty() && firstMc != null) shown.add(firstMc);
            if (shown.isEmpty()) return "";
            Set<String> mods = mods(e);
            return "at " + String.join(" | ", shown) + via(e, frames, last, shownMods) + (mods.isEmpty() ? "" : "  [" + String.join(", ", mods) + "]");
        }

        /**
         * Which other mod's code led into the failing frames, and through which Minecraft call: a crash that another
         * mod's handling triggers (Neruina killing an errored entity, whose death reaches a broken mixin) names it.
         */
        private String via(Event e, List<String[]> frames, int last, Set<String> shownMods) {
            if (last < 0) return "";
            String mc = null;
            for (int i = last + 1; i < frames.size(); i++) {
                String o = owner(e, frames.get(i));
                if (o == null) continue;
                if (o.equals("minecraft")) {
                    if (!HANDLER.matcher(frames.get(i)[1]).find()) mc = text(frames.get(i), o);
                    continue;
                }
                if (shownMods.contains(o.split(" ")[0])) {
                    mc = null;
                    continue;
                }
                return " | reached via " + (mc == null ? "" : mc + " <- ") + text(frames.get(i), o);
            }
            return "";
        }

        /** One frame as shown: Yarn class and method with line and owning mod, or a mixin handler with its mod. */
        private String text(String[] f, String owner) {
            String line = f[2].contains(":") ? ":" + f[2].substring(f[2].lastIndexOf(':') + 1) : "";
            Matcher h = HANDLER.matcher(f[1]);
            String id = owner.split(" ")[0]; // versions are listed once, after the frames
            if (h.find()) return className(f[0]) + " <- " + id + " mixin " + h.group(2);
            String[] c = cls(f[0]);
            return (c == null ? f[0].substring(f[0].lastIndexOf('.') + 1) + "." + f[1] : className(f[0]) + "." + method(c, f[1])) + line
                    + (owner.equals("minecraft") ? "" : " (" + id + ")");
        }
    }
}
