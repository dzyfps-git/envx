package dev.envx.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Regex search over text an environment actually contains: the server's mirrored configs and
 * datapacks, the data/metadata files of every loaded mod jar (already extracted at index time), and the
 * decompiled code of every loaded jar ({@link FullDecompile} fills it after a sync; until it is done, only
 * classes read before). Answers name files {@code <mod>:<path>}.
 */
public final class GrepService {
    private final QueryService q;

    public GrepService(QueryService q) {
        this.q = q;
    }

    public String grep(Scope s, String regex, String scope, String pathFilter, int budget) throws SQLException, IOException {
        Pattern p;
        try {
            p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(regex), Pattern.CASE_INSENSITIVE);
        }
        String sc = scope == null || scope.isBlank() ? "config,resources" : scope.toLowerCase(Locale.ROOT).replace(" ", "");
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        for (String part : sc.split(",")) {
            String name = switch (part) {
                case "config", "configs" -> "config";
                case "resources", "resource" -> "resources";
                case "source", "sources" -> "source";
                case "logs", "log" -> "logs";
                default -> throw new IllegalArgumentException("Unknown scope '" + part + "'. Scopes: config, resources, source, logs (comma-separated).");
            };
            scopes.add(name);
        }
        sc = String.join(",", scopes);
        String filter = pathFilter == null || pathFilter.isBlank() ? null : pathFilter.toLowerCase(Locale.ROOT).replace('\\', '/');
        List<Root> roots = new ArrayList<>();
        Path home = q.config().home();
        // With mod:, server config files count only when their path names a selected mod (config/lithium.properties).
        List<String> modIds = new ArrayList<>();
        if (s.scoped()) {
            for (long id : s.only()) {
                String modId = q.db().queryString("SELECT mod_id FROM artifact WHERE id=?", id);
                if (modId != null) modIds.add(modId.toLowerCase(Locale.ROOT));
            }
        }
        if (scopes.contains("config")) {
            // The snapshot's own stored text, so a past snapshot greps its own configs. Snapshots from before 0.3
            // have none; the current one then falls back to the mirror folder.
            List<Object[]> files = q.db().query("SELECT rel_path, sha256 FROM snapshot_text WHERE snapshot_id=?",
                    rs -> new Object[]{rs.getString(1), home.resolve("texts").resolve(rs.getString(2).substring(0, 2)).resolve(rs.getString(2))},
                    s.snapshotId());
            if (!files.isEmpty()) roots.add(new Root("server", null, modIds, files));
            else if (!s.historical()) roots.add(new Root("server", home.resolve("envs").resolve(s.env()).resolve("files"), modIds, null));
        }
        if (scopes.contains("resources")) {
            for (Object[] a : q.db().query("SELECT a.sha256, a.id FROM artifact a WHERE a.id IN (" + s.artifactSet() + ")",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2)})) {
                Path r = home.resolve("resources").resolve((String) a[0]);
                if (Files.isDirectory(r) && s.includes((long) a[1])) roots.add(new Root(q.label((long) a[1]).split(" ")[0], r, List.of(), null));
            }
        }
        String coverage = "";
        if (scopes.contains("source")) { // each loaded jar's own cache folder, never other versions' (decomp/ keeps them all)
            dev.envx.fabric.FabricBase base = dev.envx.fabric.FabricBase.forEnv(home, s.def().minecraft, s.def().mappings);
            for (Object[] a : q.db().query("SELECT a.id, a.kind, a.sha256 FROM artifact a WHERE a.id IN (" + s.artifactSet() + ")",
                    rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)})) {
                Path r = SourceService.cacheDir(home, base, (String) a[1], (String) a[2]);
                if (Files.isDirectory(r) && s.includes((long) a[0])) roots.add(new Root(q.label((long) a[0]).split(" ")[0], r, List.of(), null));
            }
            coverage = FullDecompile.coverage(q, s).note();
        }

        if (scopes.contains("logs") && !s.historical()) { // the server's recent logs and crash reports (runtime evidence)
            dev.envx.env.LogMirror.refresh(q.config(), s.env(), dev.envx.env.LogMirror.QUERY_MAX_AGE);
            Path d = dev.envx.env.LogMirror.dir(home, s.env());
            for (String sub : List.of("logs", "crash-reports")) {
                if (Files.isDirectory(d.resolve(sub))) roots.add(new Root(sub, d.resolve(sub), List.of(), null));
            }
        }

        final Pattern pattern = p;
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean capped = new java.util.concurrent.atomic.AtomicBoolean();
        // A file's name can be the answer when its text never repeats it (an advancement's id is its file name).
        // A pattern that matches nonsense (".") would name every file, so names are only collected for real patterns.
        boolean names = !pattern.matcher("").find() && !pattern.matcher("qzx~").find();
        List<String> nameHits = Collections.synchronizedList(new ArrayList<>());
        roots.parallelStream().forEach(root -> {
            try (Stream<Object[]> walk = root.files()) {
                walk.forEach(entry -> {
                    String rel = (String) entry[0];
                    Path f = (Path) entry[1];
                    if (hits.size() >= MAX_HITS) { // all decompiled code holds millions of lines; a broad pattern stops here
                        capped.set(true);
                        return;
                    }
                    String lower = rel.toLowerCase(Locale.ROOT);
                    // Answers show files as <label>:<path>; a path copied from there filters too.
                    if (filter != null && !lower.contains(filter) && !(root.label.toLowerCase(Locale.ROOT) + ":" + lower).contains(filter)) return;
                    if (!root.pathMustName.isEmpty() && root.pathMustName.stream().noneMatch(lower::contains)) return;
                    try {
                        List<String> lines = rel.endsWith(".gz") ? LogService.read(f) : Files.readAllLines(f, StandardCharsets.UTF_8);
                        List<Integer> matched = new ArrayList<>();
                        if (names && pattern.matcher(rel).find()) nameHits.add(root.label + ":" + rel);
                        for (int i = 0; i < lines.size(); i++) {
                            if (pattern.matcher(lines.get(i)).find()) {
                                matched.add(i);
                                hits.add(new Hit(root.label + ":" + rel, i + 1, clip(lines.get(i)), false));
                            }
                        }
                        // A match in a small JSON object or array shows the whole block, so a map is never read in
                        // part (Q16: "advancements" showed one of its two entries and the agent took it for the map).
                        if (!matched.isEmpty() && JSON.matcher(lower).find()) {
                            java.util.Set<Integer> shown = new java.util.HashSet<>(matched);
                            for (int i : matched) {
                                int[] block = block(lines, i);
                                if (block == null) continue;
                                for (int j = block[0]; j <= block[1]; j++) {
                                    if (shown.add(j)) hits.add(new Hit(root.label + ":" + rel, j + 1, clip(lines.get(j)), true));
                                }
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // binary or malformed file: skip
                    }
                });
            } catch (IOException ignored) {
                // directory vanished: skip
            }
        });
        java.util.Set<String> hitFiles = new java.util.HashSet<>();
        for (Hit h : hits) hitFiles.add(h.file());
        List<String> namedOnly = new ArrayList<>(nameHits.stream().filter(f -> !hitFiles.contains(f)).sorted().toList());
        if (hits.isEmpty() && !namedOnly.isEmpty()) {
            Out out = new Out(budget);
            out.force("No text matches for /" + regex + "/ in " + sc + s.scopeNote() + "; " + namedOnly.size() + " file(s) whose path matches:");
            for (String f : namedOnly) out.tallied("  " + f, f, "file");
            return out.finish("read one with grep . --path <path>");
        }
        if (hits.isEmpty()) {
            return "No matches for /" + regex + "/ in " + sc + s.scopeNote() + (filter == null ? "" : " (path contains '" + filter + "')") + coverage + "."
                    + (scopes.contains("source") ? "" : " Decompiled code: add scope=source.")
                    + (scopes.contains("resources") && !s.scoped() ? q.pastResources(s, pattern, filter) : "");
        }
        // Grouped by file: the path once, then line numbers in order. Reading a whole file with "." stays compact
        // and in order (0.2.1 repeated a ~70-char path on every line and sorted line 1 after line 19).
        hits.sort(java.util.Comparator.comparing(Hit::file).thenComparingInt(Hit::line));
        long files = hits.stream().map(Hit::file).distinct().count();
        long context = hits.stream().filter(Hit::context).count();
        Out out = new Out(budget);
        out.force((hits.size() - context) + " match(es) in " + files + " file(s), " + sc + s.scopeNote()
                + (capped.get() ? "; stopped after " + MAX_HITS + " matches, so counts are partial: narrow with mod: or path=" : "")
                + coverage + (context > 0 ? "; lines marked N- complete the JSON block around a match" : ""));
        String current = null;
        for (Hit h : hits) {
            if (!h.file().equals(current)) {
                out.line("== " + h.file(), h.file(), null);
                current = h.file();
            }
            out.tallied("  " + h.line() + (h.context() ? "- " : ": ") + h.text(), h.file(), "line");
        }
        if (!namedOnly.isEmpty()) {
            out.force("also " + namedOnly.size() + " file(s) whose path matches but not their text: "
                    + String.join(", ", namedOnly.subList(0, Math.min(6, namedOnly.size()))) + (namedOnly.size() > 6 ? ", ..." : ""));
        }
        return out.finish("narrow with path=<substring> or scope=config|resources|source|logs");
    }

    private record Hit(String file, int line, String text, boolean context) {}

    /** Matching lines collected before a search stops (answers show a few dozen; this bounds memory and time). */
    static final int MAX_HITS = 20_000;

    private static final Pattern JSON = Pattern.compile("\\.(json5?|mcmeta)$");
    /** Blocks up to this many lines are shown whole around a match. */
    static final int BLOCK_LINES = 12;

    private static String clip(String line) {
        String text = line.trim();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }

    /**
     * The lines of the smallest JSON object or array that the match opens or sits in, when it has at most
     * {@link #BLOCK_LINES} lines and is not the file's top level; null otherwise. Brackets inside strings and
     * {@code //} comments are ignored.
     */
    static int[] block(List<String> lines, int at) {
        int open = -1;
        if (depthChange(lines.get(at))[1] > 0) open = at; // the match opens a block
        else {
            int depth = 0;
            for (int i = at - 1; i >= 0 && at - i <= BLOCK_LINES; i--) {
                int[] d = depthChange(lines.get(i));
                depth += d[0] - d[1]; // walking backwards: closes add, opens subtract
                if (depth < 0) {
                    open = i;
                    break;
                }
            }
        }
        if (open <= 0) return null; // not found, or the top-level object
        int depth = 0;
        for (int j = open; j < lines.size() && j - open < BLOCK_LINES; j++) {
            int[] d = depthChange(lines.get(j));
            depth += d[1] - d[0];
            if (depth <= 0 && j > open) return new int[]{open, j};
            if (depth <= 0 && j == open) return null; // opened and closed on one line: already visible
        }
        return null;
    }

    /** {closes, opens} of brackets on a line outside strings and // comments. */
    private static int[] depthChange(String line) {
        int opens = 0, closes = 0;
        boolean str = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
            else if (c == '{' || c == '[') opens++;
            else if (c == '}' || c == ']') {
                if (opens > 0) opens--;
                else closes++;
            }
        }
        return new int[]{closes, opens};
    }

    /**
     * What to search: a directory, or an explicit list of {rel path, stored file} (a snapshot's text). If
     * {@code pathMustName} is non-empty, only files whose path contains one of those names count.
     */
    private record Root(String label, Path dir, List<String> pathMustName, List<Object[]> list) {
        Stream<Object[]> files() throws IOException {
            if (list != null) return list.stream();
            return Files.walk(dir).filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith(".") && !f.getFileName().toString().endsWith(".tmp")) // markers, writes in progress
                    .map(f -> new Object[]{dir.relativize(f).toString().replace('\\', '/'), f});
        }
    }
}
