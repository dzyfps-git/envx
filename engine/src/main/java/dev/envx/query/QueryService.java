package dev.envx.query;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.envx.Config;
import dev.envx.store.Db;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * All read-only queries. Answers are compact, line-oriented text sized for an agent's context:
 * Yarn names first, runtime (intermediary) names in brackets, the owning mod and version on each line.
 */
public final class QueryService {
    private static final Pattern INTERMEDIARY_CLASS = Pattern.compile("class_\\d+(\\$class_\\d+)*");

    private final Config config;
    private final Db db;
    private final Map<Long, String> labels = new HashMap<>();
    private final PastHint past = new PastHint(this);

    public QueryService(Config config, Db db) {
        this.config = config;
        this.db = db;
    }

    public Db db() {
        return db;
    }

    /** "" or a line saying which past versions had a class that the current server lacks. */
    public String pastClasses(Scope s, String cls) {
        return past.classes(s, cls);
    }

    /** "" or a line saying which past versions had a mod that the current server lacks. */
    public String pastMods(Scope s, String term) {
        return past.mods(s, term);
    }

    String pastResources(Scope s, java.util.regex.Pattern p, String pathFilter) {
        return past.resources(s, p, pathFilter);
    }

    public Config config() {
        return config;
    }

    // ---------------------------------------------------------------- lookup helpers

    public record ClassRow(long id, String name, String named, long artifactId, String superName, String interfaces, int access) {}

    public record MemberRow(long classId, String kind, String name, String desc, String named, String namedDesc, int access, Integer line) {}

    /** Class lookup across the whole environment (for resolving targets). */
    public List<ClassRow> findClasses(Scope s, String cls, int limit) throws SQLException {
        return findClasses(s, cls, limit, false);
    }

    /** Class lookup limited to the {@code mod:} selection (for listing results), when there is one. */
    public List<ClassRow> findResultClasses(Scope s, String cls, int limit) throws SQLException {
        return findClasses(s, cls, limit, true);
    }

    private List<ClassRow> findClasses(Scope s, String cls, int limit, boolean resultsOnly) throws SQLException {
        String sql = "SELECT id, name, named, artifact_id, super, interfaces, access FROM class c WHERE "
                + (resultsOnly ? s.results("c") : s.artifacts("c")) + " AND ";
        Db.RowMapper<ClassRow> map = rs -> new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getInt(7));
        String q = cls.trim();
        if (INTERMEDIARY_CLASS.matcher(q).matches()) {
            return db.query(sql + "c.name = ? LIMIT " + limit, map, "net/minecraft/" + q);
        }
        if (q.contains(".") || q.contains("/")) {
            String internal = q.replace('.', '/');
            List<ClassRow> rows = db.query(sql + "(c.name = ? OR c.named = ? COLLATE NOCASE) LIMIT " + limit, map, internal, internal);
            if (rows.isEmpty() && internal.contains("/")) { // Outer.Inner written with a dot
                int last = internal.lastIndexOf('/');
                String inner = internal.substring(0, last) + "$" + internal.substring(last + 1);
                rows = db.query(sql + "(c.name = ? OR c.named = ? COLLATE NOCASE) LIMIT " + limit, map, inner, inner);
            }
            if (rows.isEmpty() && internal.contains("/")) { // wrong package guessed: the class's own name still finds it
                String simple = internal.substring(internal.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                rows = db.query(sql + "c.simple = ? ORDER BY c.artifact_id LIMIT " + limit, map, simple);
            }
            return rows;
        }
        String simple = q.toLowerCase(Locale.ROOT);
        if (simple.contains("*")) {
            return db.query(sql + "c.simple LIKE ? ORDER BY length(c.simple) LIMIT " + limit, map, simple.replace('*', '%'));
        }
        return db.query(sql + "c.simple = ? ORDER BY c.artifact_id LIMIT " + limit, map, simple);
    }

    public List<MemberRow> members(long classId) throws SQLException {
        return db.query("SELECT class_id, kind, name, descriptor, named, named_desc, access, line FROM member WHERE class_id=? ORDER BY kind DESC, named",
                QueryService::memberRow, classId);
    }

    private static MemberRow memberRow(java.sql.ResultSet rs) throws SQLException {
        Integer line = rs.getObject(8) == null ? null : rs.getInt(8);
        return new MemberRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), line);
    }

    /** Members of {@code cls} matching {@code member} (runtime or display name), walking up superclasses if needed. */
    public record Resolved(ClassRow owner, MemberRow member, String inheritedFrom) {}

    /**
     * Breadth-first over the class, then its superclasses and interfaces (default methods such as
     * {@code ServerWorldAccess.spawnEntityAndPassengers} live on interfaces). Stops at the first level with a match.
     */
    public List<Resolved> resolveMember(Scope s, ClassRow cls, String member, String desc) throws SQLException {
        List<Resolved> out = new ArrayList<>();
        List<ClassRow> level = List.of(cls);
        java.util.Set<String> visited = new java.util.HashSet<>();
        String like = member.contains("*") ? member.replace('*', '%') : null;
        for (int depth = 0; !level.isEmpty() && depth < 12 && (like != null || out.isEmpty()); depth++) {
            List<ClassRow> next = new ArrayList<>();
            for (ClassRow cur : level) {
                if (!visited.add(cur.name())) continue;
                List<MemberRow> rows = like != null
                        ? db.query("SELECT class_id, kind, name, descriptor, named, named_desc, access, line FROM member WHERE class_id=? AND (name LIKE ? OR named LIKE ?)",
                        QueryService::memberRow, cur.id(), like, like)
                        : db.query("SELECT class_id, kind, name, descriptor, named, named_desc, access, line FROM member WHERE class_id=? AND (name = ? OR named = ? COLLATE NOCASE)",
                        QueryService::memberRow, cur.id(), member, member);
                for (MemberRow m : rows) {
                    if (desc == null || desc.equals(m.desc()) || desc.equals(m.namedDesc())) {
                        out.add(new Resolved(cur, m, depth == 0 ? null : cur.named()));
                    }
                }
                List<String> parents = new ArrayList<>();
                if (cur.superName() != null) parents.add(cur.superName());
                if (cur.interfaces() != null) parents.addAll(List.of(cur.interfaces().split(",")));
                for (String p : parents) {
                    if (p.startsWith("java/")) continue;
                    next.addAll(db.query("SELECT id, name, named, artifact_id, super, interfaces, access FROM class c WHERE c.name=? AND " + s.artifacts("c") + " LIMIT 1",
                            rs -> new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)), p));
                }
            }
            level = next;
        }
        return out;
    }

    public String label(long artifactId) throws SQLException {
        String l = labels.get(artifactId);
        if (l == null) {
            l = db.queryString("""
                    SELECT CASE WHEN kind='minecraft' THEN 'minecraft' WHEN mod_id IS NOT NULL THEN mod_id || ' ' || coalesce(mod_version,'')
                    ELSE file_name END FROM artifact WHERE id=?""", artifactId);
            labels.put(artifactId, l);
        }
        return l;
    }

    static String classLine(ClassRow c) {
        String named = Sig.dotted(c.named());
        return named.equals(Sig.dotted(c.name())) ? named : named + "  [" + Sig.simple(c.name()) + "]";
    }

    String memberLine(ClassRow owner, MemberRow m) {
        String sig = m.kind().equals("m") ? Sig.method(m.namedDesc()) : ": " + Sig.field(m.namedDesc());
        String runtime = m.name().equals(m.named()) ? "" : "  [" + Sig.simple(owner.name()) + "." + m.name() + "]";
        String line = m.line() == null ? "" : "  L" + m.line();
        return Sig.simple(owner.named()) + "." + m.named() + sig + runtime + line;
    }

    // ---------------------------------------------------------------- find

    public String find(Scope s, String query, int budget) throws SQLException {
        String q = query.trim();
        if (q.startsWith("mod:")) return mods(s, q.substring(4).trim(), budget);
        if (isSearch(s, q)) return search(s, q, budget);
        Out out = new Out(budget);
        Target t = Target.parse(q);
        if (t.cls() != null) {
            List<ClassRow> classes = findResultClasses(s, t.cls(), 50);
            if (classes.isEmpty()) return "No class matching '" + t.cls() + "' in " + s.env() + s.scopeNote() + ". Try a wildcard (*Tick*) or add mod:<id>."
                    + past.classes(s, t.cls());
            if (t.member() == null) {
                for (ClassRow c : classes) {
                    String ext = c.superName() == null || c.superName().equals("java/lang/Object") ? "" : "  extends " + Sig.simple(named(c.superName()));
                    out.line("c " + classLine(c) + ext + "  | " + label(c.artifactId()));
                }
                return out.finish("narrow with a package or member");
            }
            int found = 0;
            for (ClassRow c : classes) {
                for (Resolved r : resolveMember(s, c, t.member(), t.desc())) {
                    found++;
                    String inh = r.inheritedFrom() == null ? "" : "  (inherited from " + Sig.simple(r.inheritedFrom()) + ")";
                    out.line(r.member().kind() + " " + Sig.dotted(r.owner().named()).replaceAll("\\.[^.]+$", ".") + memberLine(r.owner(), r.member())
                            + inh + "  | " + label(r.owner().artifactId()));
                }
            }
            if (found == 0) return "Class found but no member '" + t.member() + "' in " + classes.size() + " candidate(s): "
                    + classes.stream().limit(5).map(c -> Sig.dotted(c.named())).toList() + ". Try outline with filter=.";
            return out.finish("add a descriptor or owner to narrow");
        }
        // Member without a class: global lookup by runtime or display name.
        String m = t.member() != null ? t.member() : q;
        int total = db.queryInt("SELECT count(*) FROM member mm JOIN class c ON c.id=mm.class_id WHERE (mm.name=? OR mm.named=? COLLATE NOCASE) AND " + s.results("c"), m, m);
        var rows = db.query("""
                SELECT c.id, c.name, c.named, c.artifact_id, c.super, c.interfaces, c.access,
                       mm.class_id, mm.kind, mm.name, mm.descriptor, mm.named, mm.named_desc, mm.access, mm.line
                FROM member mm JOIN class c ON c.id=mm.class_id
                JOIN artifact a ON a.id=c.artifact_id
                WHERE (mm.name=? OR mm.named=? COLLATE NOCASE) AND %s
                ORDER BY a.kind='minecraft' DESC, length(c.named) LIMIT 60""".formatted(s.results("c")),
                QueryService::resolvedRow, m, m);
        if (rows.isEmpty()) return "No member named '" + m + "' in " + s.env() + s.scopeNote() + ". For a keyword search use *" + m + "* or add mod:<id>.";
        out.force(total + " match(es) for member '" + m + "'" + s.scopeNote() + (total > rows.size() ? ", showing " + rows.size() : ""));
        for (Resolved r : rows) out.line(r.member().kind() + " " + Sig.dotted(r.owner().named()).replaceAll("\\.[^.]+$", ".")
                + memberLine(r.owner(), r.member()) + "  | " + label(r.owner().artifactId()));
        if (total > rows.size()) out.dropped(total - rows.size());
        return out.finish("qualify with a class, e.g. LivingEntity." + m);
    }

    /** A class row joined with one of its members (columns: 7 class, then 8 member). */
    private static Resolved resolvedRow(java.sql.ResultSet rs) throws SQLException {
        return new Resolved(new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)),
                new MemberRow(rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getInt(14),
                        rs.getObject(15) == null ? null : rs.getInt(15)), null);
    }

    /** A wildcard, or a bare keyword inside a mod: selection, searches names instead of resolving one symbol. */
    static boolean isSearch(Scope s, String q) {
        if (q.contains(".") || q.contains("#") || q.contains("(") || q.contains("/")) return false;
        return q.contains("*") || s.scoped();
    }

    /**
     * Name search over classes and members (substring, or * wildcards), members grouped by class.
     * On a server environment, client-only packages are left out (with a count). What does not fit is
     * still listed by name at the end, so a capped answer never hides a match completely.
     */
    private String search(Scope s, String q, int budget) throws SQLException {
        String like = q.contains("*") ? q.toLowerCase(Locale.ROOT).replace('*', '%') : "%" + q.toLowerCase(Locale.ROOT) + "%";
        String classWhere = "c.simple LIKE ? AND c.simple NOT GLOB '*$[0-9]*' AND " + s.results("c");
        String memberWhere = "mm.named LIKE ? AND mm.named NOT LIKE 'lambda$%' AND (mm.access & 4096)=0 AND " + s.results("c");
        String sideFilter = "server".equals(s.def().side) ? " AND coalesce(c.named, c.name) NOT LIKE '%/client/%'" : "";
        int classAll = db.queryInt("SELECT count(*) FROM class c WHERE " + classWhere, like);
        int memberAll = db.queryInt("SELECT count(*) FROM member mm JOIN class c ON c.id=mm.class_id WHERE " + memberWhere, like);
        classWhere += sideFilter;
        memberWhere += sideFilter;
        int classTotal = db.queryInt("SELECT count(*) FROM class c WHERE " + classWhere, like);
        int memberTotal = db.queryInt("SELECT count(*) FROM member mm JOIN class c ON c.id=mm.class_id WHERE " + memberWhere, like);
        List<ClassRow> classes = db.query("SELECT id, name, named, artifact_id, super, interfaces, access FROM class c WHERE " + classWhere
                        + " ORDER BY length(c.simple), c.named LIMIT 200",
                rs -> new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)), like);
        var members = db.query("""
                SELECT c.id, c.name, c.named, c.artifact_id, c.super, c.interfaces, c.access,
                       mm.class_id, mm.kind, mm.name, mm.descriptor, mm.named, mm.named_desc, mm.access, mm.line
                FROM member mm JOIN class c ON c.id=mm.class_id WHERE %s ORDER BY c.named, mm.named LIMIT 400""".formatted(memberWhere),
                QueryService::resolvedRow, like);
        int hidden = (classAll - classTotal) + (memberAll - memberTotal);
        // Names are not the whole story in one mod: code that calls a matching API and data files that mention the
        // keyword do the same job (Q16: spawns via BiomeModifications.addSpawn and structure spawn_overrides).
        List<String> hints = new ArrayList<>();
        if (s.scoped()) {
            try {
                hints.addAll(outsideCalls(s, q));
                hints.addAll(dataFiles(s, q));
            } catch (IOException | RuntimeException e) {
                // hints only; the name search stands
            }
        }
        if (classes.isEmpty() && members.isEmpty()) {
            String none = "Nothing named like '" + q + "' in " + s.env() + s.scopeNote()
                    + (hidden > 0 ? " outside client-only packages (" + hidden + " client-side match(es) hidden on this " + s.def().side + ")" : "") + "."
                    + (s.scoped() ? "" : past.classes(s, q.contains("*") ? q : "*" + q + "*"));
            if (hints.isEmpty()) return none;
            Out out = new Out(budget);
            out.force(none);
            hints.forEach(out::line);
            return out.finish(null);
        }
        // One mod in the results (the usual mod: case): name it once in the header instead of on every line.
        java.util.Set<Long> artifacts = new java.util.HashSet<>();
        classes.forEach(c -> artifacts.add(c.artifactId()));
        members.forEach(r -> artifacts.add(r.owner().artifactId()));
        String only = artifacts.size() == 1 ? label(artifacts.iterator().next()) : null;
        Out out = new Out(budget);
        out.force("search '" + q + "'" + s.scopeNote() + ": " + classTotal + " class(es), " + memberTotal + " member(s) with matching names"
                + (only != null ? ", all in " + only : "")
                + (hidden > 0 ? "; " + hidden + " client-side hidden (" + s.def().side + " environment)" : ""));
        for (ClassRow c : classes) out.line("c " + classLine(c) + (only != null ? "" : "  | " + label(c.artifactId())), "classes", Sig.simple(c.named()));
        if (classTotal > classes.size()) out.dropped(classTotal - classes.size());
        hints.forEach(out::line); // before the members, so a long member list cannot push them out
        long lastClass = -1;
        for (Resolved r : members) {
            String cls = Sig.simple(r.owner().named());
            if (r.owner().id() != lastClass) {
                out.line("in " + Sig.dotted(r.owner().named()) + (only != null ? "" : "  | " + label(r.owner().artifactId())), cls, null);
                lastClass = r.owner().id();
            }
            MemberRow mr = r.member();
            String sig = mr.kind().equals("m") ? Sig.method(mr.namedDesc()) : ": " + Sig.field(mr.namedDesc());
            String text = "  " + mr.kind() + " " + mr.named() + sig + (mr.name().equals(mr.named()) ? "" : "  [" + mr.name() + "]");
            if (mr.kind().equals("m")) out.line(text, cls, mr.named());
            else out.tallied(text, cls, "field"); // methods are what an agent follows next; fields are only counted
        }
        if (memberTotal > members.size()) out.dropped(memberTotal - members.size());
        return out.finish(s.scoped() ? "narrow the pattern (e.g. *onTick*) or outline a class" : "narrow the pattern or add mod:<id>");
    }

    /** The selected mods' calls to methods elsewhere whose display names match {@code q}, grouped by callee. */
    private List<String> outsideCalls(Scope s, String q) throws SQLException, IOException {
        java.util.function.Predicate<String> kw = keyword(q);
        java.util.Set<String> own = new java.util.HashSet<>(db.query("SELECT name FROM class c WHERE " + s.results("c"), rs -> rs.getString(1)));
        List<ClassRow> users = db.query("SELECT id, name, named, artifact_id, super, interfaces, access FROM class c WHERE " + s.results("c"),
                rs -> new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)));
        Map<String, String> memberNames = new HashMap<>();
        Map<String, String> classNames = new HashMap<>();
        Map<String, java.util.Set<String>> calls = new TreeMap<>(); // callee -> callers
        scan(users, (u, mn, line, o, n, d, callers) -> {
            if (n == null || d == null || !d.startsWith("(") || n.startsWith("<") || own.contains(o) || o.startsWith("java/")) return;
            String name = n;
            if (n.startsWith("method_")) { // intermediary names are unique across Minecraft: one lookup gives the Yarn name
                name = memberNames.get(n);
                if (name == null) {
                    name = db.queryString("SELECT named FROM member WHERE name=? LIMIT 1", n);
                    memberNames.put(n, name = name == null ? n : name);
                }
            }
            if (!kw.test(name.toLowerCase(Locale.ROOT))) return;
            String cls = classNames.get(o);
            if (cls == null) classNames.put(o, cls = Sig.simple(named(o)));
            String caller = mn.name.startsWith("lambda$") ? mn.name.split("\\$")[1] : callerSig(mn, callers).replaceAll("\\(.*", "");
            calls.computeIfAbsent(cls + "." + name, k -> new java.util.TreeSet<>()).add(Sig.simple(u.named()) + "." + caller + (line > 0 ? " L" + line : ""));
        });
        List<String> lines = new ArrayList<>();
        if (calls.isEmpty()) return lines;
        lines.add("calls to other code named like '" + q + "' (" + calls.size() + "):");
        for (var e : calls.entrySet()) {
            if (lines.size() > CALLEES_SHOWN) {
                lines.add("  +" + (calls.size() - CALLEES_SHOWN) + " more: " + String.join(", ", calls.keySet().stream().skip(CALLEES_SHOWN).limit(10).toList()));
                break;
            }
            List<String> from = new ArrayList<>(e.getValue());
            lines.add("  " + e.getKey() + "  <- " + String.join(", ", from.subList(0, Math.min(3, from.size())))
                    + (from.size() > 3 ? " +" + (from.size() - 3) : ""));
        }
        return lines;
    }

    private static final int CALLEES_SHOWN = 12;

    /** Text data files of the selected mods that mention {@code q}, counted per folder, with the grep that shows them. */
    private List<String> dataFiles(Scope s, String q) throws SQLException, IOException {
        String needle = q.replace("*", "").toLowerCase(Locale.ROOT);
        if (needle.length() < 3) return List.of();
        boolean server = "server".equals(s.def().side);
        Map<String, Integer> byFolder = new TreeMap<>();
        Map<String, String> sample = new HashMap<>(); // folder -> first file's matching text, so the count says what it is
        List<String> mods = new ArrayList<>();
        int files = 0, scanned = 0;
        for (long id : s.only()) {
            String sha = db.queryString("SELECT sha256 FROM artifact WHERE id=?", id);
            String modId = db.queryString("SELECT mod_id FROM artifact WHERE id=?", id);
            int before = files;
            Path root = config.home().resolve("resources").resolve(sha);
            if (!Files.isDirectory(root)) continue;
            List<Path> candidates;
            try (var walk = Files.walk(root)) {
                // Client resources are left out on a server environment; files at the jar root are its metadata.
                candidates = walk.filter(Files::isRegularFile).filter(f -> DATA_FILE.matcher(f.getFileName().toString()).find())
                        .filter(f -> {
                            String rel = root.relativize(f).toString().replace('\\', '/');
                            return rel.contains("/") && !(server && rel.startsWith("assets/"));
                        }).limit(DATA_SCAN_LIMIT).toList();
            }
            for (Path f : candidates) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (++scanned > DATA_SCAN_LIMIT || Files.size(f) > 1 << 20) continue;
                String raw;
                try {
                    raw = Files.readString(f);
                } catch (IOException | RuntimeException notText) {
                    continue;
                }
                int at = raw.toLowerCase(Locale.ROOT).indexOf(needle);
                if (at < 0) continue;
                files++;
                String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
                String folder = dir.replaceFirst("^data/[^/]+/", "");
                byFolder.merge(folder, 1, Integer::sum);
                sample.computeIfAbsent(folder, k -> {
                    // From the start of the matching line, pretty-printed data folded onto one line.
                    int from = raw.lastIndexOf('\n', at) + 1;
                    String text = raw.substring(from, Math.min(raw.length(), from + 600)).replaceAll("\\s*\\R\\s*", "").replaceAll("\\s{2,}", " ").trim();
                    return rel.substring(rel.lastIndexOf('/') + 1) + ": " + (text.length() > SAMPLE_CHARS ? text.substring(0, SAMPLE_CHARS) + "…" : text);
                });
            }
            if (files > before && modId != null) mods.add(modId);
        }
        if (files == 0) return List.of();
        List<Map.Entry<String, Integer>> folders = byFolder.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        List<String> lines = new ArrayList<>();
        lines.add("data files mentioning '" + needle + "' (" + files + "; grep " + needle + " scope=resources mod:" + String.join(",", mods) + "):");
        for (var e : folders.subList(0, Math.min(FOLDERS_SHOWN, folders.size()))) {
            lines.add("  " + e.getKey() + " x" + e.getValue() + ", e.g. " + sample.get(e.getKey()));
        }
        if (folders.size() > FOLDERS_SHOWN) {
            lines.add("  +" + (folders.size() - FOLDERS_SHOWN) + " folders: " + String.join(", ", folders.stream().skip(FOLDERS_SHOWN).limit(10)
                    .map(e -> e.getKey() + " x" + e.getValue()).toList()));
        }
        return lines;
    }

    private static final int FOLDERS_SHOWN = 4;
    private static final int SAMPLE_CHARS = 140;

    private static final Pattern DATA_FILE = Pattern.compile("\\.(json5?|mcfunction|toml|properties|ya?ml|cfg|txt|snbt)$");
    private static final int DATA_SCAN_LIMIT = 5000;

    /** A name matcher for a search term: substring, or a * wildcard pattern over the whole name. */
    private static java.util.function.Predicate<String> keyword(String q) {
        String t = q.toLowerCase(Locale.ROOT);
        if (!t.contains("*")) return n -> n.contains(t);
        Pattern p = Pattern.compile(Arrays.stream(t.split("\\*", -1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*")));
        return n -> p.matcher(n).matches();
    }

    /** Display name for a runtime class name, via the index. */
    String named(String runtime) throws SQLException {
        if (runtime == null) return null;
        String n = db.queryString("SELECT named FROM class WHERE name=? LIMIT 1", runtime);
        return n == null ? runtime : n;
    }

    // ---------------------------------------------------------------- mods / env

    /**
     * Mods matching {@code filter}. A comma-separated list ({@code lithium,spark,fabric-api}) answers
     * several mods in one call with one line each; a single exact id gives the mod's details.
     */
    public String mods(Scope s, String rawFilter, int budget) throws SQLException {
        String filter = rawFilter != null && rawFilter.startsWith("mod:") ? rawFilter.substring(4) : rawFilter;
        if (filter != null && filter.contains(",")) {
            Out out = new Out(budget);
            for (String term : filter.split(",")) {
                String t = term.trim();
                if (t.isEmpty()) continue;
                var rows = modRows(s, t);
                var exact = rows.stream().filter(r -> t.equalsIgnoreCase((String) r[1])).toList();
                List<String> others = exact.isEmpty() ? List.of()
                        : rows.stream().map(r -> (String) r[1]).filter(id -> !t.equalsIgnoreCase(id)).distinct().toList();
                if (!exact.isEmpty()) rows = exact;
                if (rows.isEmpty()) {
                    String runtime = loaderListVersion(s, t); // fabricloader, java, minecraft are not jars in mods/
                    out.line(runtime != null ? t + " " + runtime + "  (from the server's loader log)"
                            : t + ": no matching mod" + past.mods(s, t).replace("\n", " "));
                }
                for (Object[] r : rows.subList(0, Math.min(rows.size(), 5))) out.line(modLine(r));
                if (rows.size() > 5) out.line("  … " + (rows.size() - 5) + " more matching '" + t + "'");
                if (!others.isEmpty()) out.line("  also matching '" + t + "': " + String.join(", ", others.subList(0, Math.min(5, others.size()))));
            }
            return out.finish(null);
        }
        Out out = new Out(budget);
        var rows = modRows(s, filter);
        if (rows.isEmpty()) return "No mod matching '" + filter + "' in " + s.env() + "." + past.mods(s, filter);
        if (rows.size() == 1 || (filter != null && rows.stream().anyMatch(r -> filter.equalsIgnoreCase((String) r[1])))) {
            String f = filter;
            Object[] r = rows.stream().filter(x -> f != null && f.equalsIgnoreCase((String) x[1])).findFirst().orElse(rows.getFirst());
            String detail = modDetail(s, r, budget);
            // An exact id hides substring matches (filter=origins must not hide origins-plus-plus): name them, once.
            List<String> others = rows.stream().filter(x -> x != r).map(x -> (String) x[1]).distinct().toList();
            if (!others.isEmpty()) {
                detail += (detail.endsWith("\n") ? "" : "\n") + "also matching '" + filter + "': "
                        + String.join(", ", others.subList(0, Math.min(5, others.size()))) + (others.size() > 5 ? ", +" + (others.size() - 5) + " more" : "") + "\n";
            }
            return detail;
        }
        for (Object[] r : rows) out.line(modLine(r));
        return out.finish("filter further, or list several: a,b,c");
    }

    /** Version of {@code id} in the loader's resolved list (logs/latest.log), or null. */
    /** A version from the loader's own list (java, minecraft, fabricloader are not jars in mods/), for this snapshot. */
    private String loaderListVersion(Scope s, String id) {
        try {
            String v = db.queryString("SELECT version FROM snapshot_loaded WHERE snapshot_id=? AND lower(mod_id)=lower(?) LIMIT 1", s.snapshotId(), id);
            if (v != null || s.historical()) return v;
            Path f = config.home().resolve("envs").resolve(s.env()).resolve("loaded-mods.txt"); // snapshots from before 0.3
            if (!Files.exists(f)) return null;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.trim().split(" ", 2);
                if (p.length == 2 && p[0].equalsIgnoreCase(id)) return p[1];
            }
        } catch (IOException | SQLException ignored) {
            // no loader list: treat as unknown
        }
        return null;
    }

    private static String modLine(Object[] r) {
        return r[1] + " " + r[2] + (r[3] == null ? "" : "  \"" + r[3] + "\"") + "  (" + r[4] + ")"
                + ((int) r[7] == 1 ? "" : "  [nested]") + ((int) r[6] == 1 ? "" : "  [NOT LOADED]");
    }

    private List<Object[]> modRows(Scope s, String filter) throws SQLException {
        String f = "%" + (filter == null ? "" : filter.toLowerCase(Locale.ROOT)) + "%";
        return db.query("""
                SELECT a.id, a.mod_id, a.mod_version, a.mod_name, a.file_name, a.meta_json, sa.loaded,
                       EXISTS(SELECT 1 FROM snapshot_file sf WHERE sf.snapshot_id=sa.snapshot_id AND sf.sha256=a.sha256)
                FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND a.mod_id IS NOT NULL
                  AND (lower(a.mod_id) LIKE ? OR lower(coalesce(a.mod_name,'')) LIKE ? OR lower(a.file_name) LIKE ?)
                ORDER BY a.mod_id""",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8)},
                s.snapshotId(), f, f, f);
    }

    private String modDetail(Scope s, Object[] r, int budget) throws SQLException {
        Out out = new Out(budget);
        long id = (long) r[0];
        // A nested jar names the mod that bundles it (Q6: apoli ships inside origins; "(nested jar)" alone went unreported).
        List<String> parents = (int) r[7] == 1 ? List.of() : db.query("""
                SELECT coalesce(p.mod_id || ' ' || p.mod_version, p.file_name) FROM artifact_nested n JOIN artifact p ON p.id=n.parent_id
                WHERE n.child_id=? AND p.id IN (SELECT artifact_id FROM snapshot_artifact WHERE snapshot_id=?) ORDER BY 1""",
                rs -> rs.getString(1), id, s.snapshotId());
        out.force("mod " + r[1] + " " + r[2] + (r[3] == null ? "" : " \"" + r[3] + "\"") + "  file " + r[4]
                + ((int) r[7] == 1 ? "" : parents.isEmpty() ? "  (nested jar)" : "  (jar-in-jar, bundled inside " + String.join(", ", parents) + ")")
                + ((int) r[6] == 1 ? "" : "  NOT LOADED (superseded or client-only)"));
        if (r[5] != null) {
            JsonObject meta = JsonParser.parseString((String) r[5]).getAsJsonObject();
            if (meta.has("environment")) out.line("environment: " + meta.get("environment").getAsString());
            for (String k : List.of("depends", "breaks", "conflicts")) if (meta.has(k)) out.line(k + ": " + compactJson(meta.get(k)));
            if (meta.has("entrypoints")) out.line("entrypoints: " + compactJson(meta.get("entrypoints")));
            if (meta.has("accessWidener")) out.line("accessWidener: " + meta.get("accessWidener").getAsString());
        }
        int classes = db.queryInt("SELECT count(*) FROM class WHERE artifact_id=?", id);
        int mixins = db.queryInt("SELECT count(*) FROM mixin WHERE artifact_id=?", id);
        out.line("classes: " + classes + ", mixin injections: " + mixins);
        var top = db.query("""
                SELECT target_class, count(*) n FROM mixin WHERE artifact_id=? GROUP BY target_class ORDER BY n DESC LIMIT 15""",
                rs -> new Object[]{rs.getString(1), rs.getInt(2)}, id);
        if (!top.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object[] t : top) parts.add(Sig.simple(named((String) t[0])) + "×" + t[1]);
            out.line("mixes into: " + String.join(", ", parts));
        }
        // rtrim(name, replace(name,'/','')) is SQLite's dirname: the package of each class
        var packages = db.query("""
                SELECT rtrim(name, replace(name, '/', '')) pkg, count(*) n FROM class
                WHERE artifact_id=? AND is_mixin=0 AND name NOT LIKE '%$%' GROUP BY pkg ORDER BY n DESC LIMIT 10""",
                rs -> rs.getString(1), id);
        if (!packages.isEmpty()) {
            out.line("main packages: " + String.join(", ", packages.stream().map(p -> Sig.dotted(p).replaceAll("\\.$", "")).toList())
                    + "  (search inside: find <keyword> mod:" + r[1] + ")");
        }
        var nested = db.query("SELECT coalesce(a.mod_id || ' ' || a.mod_version, a.file_name) FROM artifact_nested n JOIN artifact a ON a.id=n.child_id WHERE n.parent_id=?",
                rs -> rs.getString(1), id);
        if (!nested.isEmpty()) out.line("nested jars: " + String.join(", ", nested));
        // The server's own settings for the mod (values, not the code defaults an outline of its config class shows).
        // A file belongs to the mod when its name (or folder) under config/ etc. starts with the id, and not with a longer
        // id of another mod (create-server.toml is create's, create_new_age-server.toml is not; fancymenu/... is not).
        String modId = norm((String) r[1]);
        List<String> longer = db.query("""
                SELECT DISTINCT a.mod_id FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND a.mod_id IS NOT NULL""", rs -> norm(rs.getString(1)), s.snapshotId())
                .stream().filter(x -> x.length() > modId.length() && x.startsWith(modId)).toList();
        List<String> files = db.query("SELECT rel_path FROM snapshot_text WHERE snapshot_id=? AND instr(lower(rel_path), ?) > 0 ORDER BY rel_path",
                rs -> rs.getString(1), s.snapshotId(), ((String) r[1]).toLowerCase(Locale.ROOT).replaceAll("[-_].*", ""))
                .stream().filter(f -> {
                    String[] seg = f.split("/");
                    String name = norm(seg.length > 1 ? seg[1] : seg[0]);
                    return name.startsWith(modId) && longer.stream().noneMatch(name::startsWith);
                }).toList();
        if (!files.isEmpty()) {
            out.line("server files naming it: " + String.join(", ", files.subList(0, Math.min(4, files.size())))
                    + (files.size() > 4 ? ", +" + (files.size() - 4) + " more" : "") + "  (values: grep <key> scope=config mod:" + r[1] + ")");
        }
        return out.finish(null);
    }

    /** Mod ids and file names compared ignoring case and the -/_ spelling. */
    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String compactJson(JsonElement e) {
        String s = e.toString().replace("\"", "");
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    public String env(Scope s, String filter, int budget) throws SQLException, IOException {
        return env(s, filter, budget, null);
    }

    /** With {@code cwd} inside a mod project, the summary also compares that project with the server. */
    public String env(Scope s, String filter, int budget, Path cwd) throws SQLException, IOException {
        if (filter != null && filter.startsWith("diff:")) return new UpdateReport(this).report(s, filter.substring(5).trim(), cwd, budget);
        if (filter != null && (filter.equals("errors") || filter.startsWith("errors:"))) {
            return new LogService(this).errors(s, filter.length() > 7 ? filter.substring(7).trim() : null, budget);
        }
        if (filter != null && !filter.isBlank()) return mods(s, filter, budget);
        Out out = new Out(budget);
        String packName = db.queryString("SELECT coalesce(pack_name, '') || coalesce(' ' || label, '') FROM snapshot WHERE id=?", s.snapshotId());
        out.force("env " + s.env() + (packName == null || packName.isBlank() ? "" : ": " + packName.trim()) + " (minecraft " + s.def().minecraft
                + ", " + s.def().platform + ", mappings " + s.def().mappings + ")");
        Map<String, String> v = new LinkedHashMap<>();
        for (String id : List.of("java", "minecraft", "fabricloader", "fabric-api")) {
            String ver = loaderListVersion(s, id);
            if (ver != null) v.put(id, ver);
        }
        if (!v.isEmpty()) out.line("runtime (from server log): " + v);
        int jars = db.queryInt("SELECT count(*) FROM snapshot_file WHERE snapshot_id=?", s.snapshotId());
        int loaded = db.queryInt("SELECT count(*) FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id WHERE sa.snapshot_id=? AND sa.loaded=1 AND a.mod_id IS NOT NULL", s.snapshotId());
        String checked = db.queryString("SELECT checked_at FROM snapshot WHERE id=?", s.snapshotId());
        out.line("snapshot " + s.snapshotId() + " taken " + s.takenAt() + " (" + age(s.takenAt()) + ")"
                + (checked != null && !checked.equals(s.takenAt()) ? ", last checked " + age(checked) : "") + " from " + s.source());
        out.line(jars + " jars in mods/, " + loaded + " loaded mods incl. nested" + (s.loaderList() ? " (resolved via loader log)" : " (no loader log; nested versions unverified)"));
        if (!s.historical()) {
            String err = db.meta("autosync." + s.env() + ".error");
            if (err != null) out.line("auto-sync: " + err + "; answers use the snapshot above");
            Path diff = config.home().resolve("envs").resolve(s.env()).resolve("diffs").resolve(s.snapshotId() + ".md");
            if (Files.exists(diff)) {
                List<String> lines = Files.readAllLines(diff);
                out.line("last sync diff: " + String.join(" ", lines.stream().filter(l -> l.startsWith("## ") || l.startsWith("No ") || l.contains("first snapshot")).toList()));
            }
            // History is never mixed into answers; one line says it exists and how to ask for it.
            String current = db.queryString("SELECT label FROM snapshot WHERE id=?", s.snapshotId());
            List<String> past = db.query("SELECT label FROM snapshot WHERE env=? AND label IS NOT NULL GROUP BY label ORDER BY min(id)",
                    rs -> rs.getString(1), s.env()).stream().filter(l -> !l.equals(current)).toList();
            if (!past.isEmpty()) out.line("history: " + String.join(", ", past) + " (env=" + s.env() + "@<version>; env filter=diff:<a>..<b>"
                    + (Project.find(config, cwd) == null ? "" : " also shows what changed for this project") + ")");
            String logs = LogService.summaryLine(config.home(), s.env());
            if (logs != null) out.line(logs);
        }
        Project project = Project.find(config, cwd);
        if (project != null && project.modId() != null) for (String l : new ProjectReport(this).lines(s, project)) out.line(l);
        out.line("use: find, outline, source, refs, mixins, check_mixins, grep; env with a filter lists mods");
        return out.finish(null);
    }

    private static String age(String iso) {
        try {
            long h = java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).toHours();
            return h < 1 ? "<1h ago" : h < 48 ? h + "h ago" : (h / 24) + "d ago";
        } catch (RuntimeException e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- outline

    public String outline(Scope s, String target, String filter, int budget) throws SQLException {
        if (target.startsWith("mod:")) return mods(s, target.substring(4).trim(), budget);
        Target t = Target.parse(target);
        String clsName = t.cls() != null ? t.cls() : target;
        List<ClassRow> classes = findResultClasses(s, clsName, 10);
        if (classes.isEmpty()) return "No class matching '" + clsName + "'" + s.scopeNote() + "." + past.classes(s, clsName);
        if (classes.size() > 1 && !clsName.contains(".") && !clsName.contains("/")) {
            Out out = new Out(budget);
            out.force(classes.size() + " classes named " + clsName + "; pass the full name:");
            for (ClassRow c : classes) out.line("  " + classLine(c) + "  | " + label(c.artifactId()));
            return out.finish(null);
        }
        ClassRow c = classes.getFirst();
        Out out = new Out(budget);
        String ext = c.superName() == null ? "" : " extends " + Sig.simple(named(c.superName()));
        String impl = "";
        if (c.interfaces() != null) {
            List<String> names = new ArrayList<>();
            for (String i : c.interfaces().split(",")) names.add(Sig.simple(named(i)));
            impl = " implements " + String.join(", ", names);
        }
        out.force("class " + classLine(c) + ext + impl + "  | " + label(c.artifactId()));
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        java.util.Set<String> seen = new java.util.HashSet<>(); // name+desc already listed, so overrides are not repeated
        for (MemberRow m : members(c.id())) {
            if (!listable(m, f)) continue;
            seen.add(m.name() + m.desc());
            out.line(outlineLine(m), "", m.named());
        }
        // Inherited members: with a filter, list the matches (the object has these too); without one, say where they are.
        List<String> inheritedSummary = new ArrayList<>();
        for (ClassRow anc : ancestors(s, c)) {
            List<MemberRow> lines = new ArrayList<>();
            int count = 0;
            for (MemberRow m : members(anc.id())) {
                boolean privateMethod = m.kind().equals("m") && (m.access() & 0x0002) != 0;
                if (privateMethod || m.name().startsWith("<") || !listable(m, f) || !seen.add(m.name() + m.desc())) continue;
                count++;
                if (f != null) lines.add(m);
            }
            if (f != null && !lines.isEmpty()) {
                String from = "from " + Sig.simple(anc.named());
                out.line("  -- " + from + (anc.artifactId() == c.artifactId() ? "" : " | " + label(anc.artifactId())) + ":", from, null);
                for (MemberRow m : lines) out.line(outlineLine(m), from, m.named());
            } else if (f == null && count > 0) {
                inheritedSummary.add(Sig.simple(anc.named()) + " " + count);
            }
        }
        if (f != null) relatedFields(s, c, f, seen, out);
        if (!inheritedSummary.isEmpty()) {
            out.force("(inherited members: " + String.join(", ", inheritedSummary.subList(0, Math.min(5, inheritedSummary.size())))
                    + (inheritedSummary.size() > 5 ? ", …" : "") + "; filter= searches them too)");
        }
        int mixinCount = db.queryInt("SELECT count(*) FROM mixin mx WHERE mx.target_class=? AND " + s.artifacts("mx"), c.name());
        if (mixinCount > 0) out.force("(" + mixinCount + " mixin injections target this class; see mixins " + Sig.simple(c.named()) + ")");
        return out.finish("pass filter=<text> to show matching members only");
    }

    /** Fields shown per filtered outline because matching methods use them. */
    private static final int RELATED_FIELDS = 8;
    /** Related fields are shown only for a narrow filter, one that matches at most this many of the class's methods. */
    private static final int RELATED_FOR_METHODS = 6;

    /**
     * A filter matches names, but what a feature depends on is often a field with another name: despawning is decided
     * by {@code MobEntity.persistent}, read through {@code isPersistent()} from {@code checkDespawn()}. So the class's
     * methods that match are read from bytecode, and the fields they use (directly, or through one call to another
     * method of the same class) are listed too.
     */
    private void relatedFields(Scope s, ClassRow c, String f, java.util.Set<String> seen, Out out) throws SQLException {
        ClassNode node = classNode(c);
        if (node == null) return;
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode mn : node.methods) methods.put(mn.name + mn.desc, mn);
        Map<String, MemberRow> byKey = new HashMap<>();
        for (MemberRow m : members(c.id())) byKey.put(m.name() + m.desc(), m);
        java.util.Set<String> owners = new java.util.HashSet<>(List.of(c.name()));
        List<ClassRow> ancestors = ancestors(s, c);
        for (ClassRow a : ancestors) owners.add(a.name());
        Map<String, String> used = new LinkedHashMap<>(); // field name+desc -> "" or "via method"
        List<MethodNode> matching = new ArrayList<>();
        for (MethodNode mn : node.methods) {
            MemberRow m = byKey.get(mn.name + mn.desc);
            if (m != null && m.kind().equals("m") && listable(m, f)) matching.add(mn);
        }
        if (matching.size() > RELATED_FOR_METHODS) return; // a broad filter ("tick"): the fields would be noise
        for (MethodNode mn : matching) {
            collectFields(mn, owners, used, "");
            for (AbstractInsnNode insn : mn.instructions) { // one hop through this class's own methods (getters)
                if (insn instanceof MethodInsnNode mi && mi.owner.equals(c.name()) && methods.containsKey(mi.name + mi.desc)) {
                    MemberRow callee = byKey.get(mi.name + mi.desc);
                    collectFields(methods.get(mi.name + mi.desc), owners, used, callee == null ? mi.name : callee.named());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        for (var e : used.entrySet()) {
            if (seen.contains(e.getKey()) || lines.size() >= RELATED_FIELDS) continue;
            MemberRow field = byKey.get(e.getKey());
            for (int i = 0; field == null && i < ancestors.size(); i++) {
                for (MemberRow m : members(ancestors.get(i).id())) if ((m.name() + m.desc()).equals(e.getKey())) field = m;
            }
            if (field == null || !field.kind().equals("f")) continue;
            seen.add(e.getKey());
            lines.add(outlineLine(field) + (e.getValue().isEmpty() ? "" : "  (via " + e.getValue() + "())"));
        }
        if (lines.isEmpty()) return;
        out.line("  -- fields the matching methods use:");
        for (String l : lines) out.line(l);
    }

    private static void collectFields(MethodNode mn, java.util.Set<String> owners, Map<String, String> used, String via) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof FieldInsnNode fi && owners.contains(fi.owner)) used.putIfAbsent(fi.name + fi.desc, via);
        }
    }

    /** The class's bytecode from its artifact, or null. */
    private ClassNode classNode(ClassRow c) {
        try {
            String sha = db.queryString("SELECT sha256 FROM artifact WHERE id=?", c.artifactId());
            Path jar = config.home().resolve("artifacts").resolve(sha.substring(0, 2)).resolve(sha + ".jar");
            if (!Files.exists(jar)) return null;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry ze = zip.getEntry(c.name() + ".class");
                if (ze == null) return null;
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(ze).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                return node;
            }
        } catch (IOException | SQLException | RuntimeException e) {
            return null; // related fields are an extra; the outline stands without them
        }
    }

    private static boolean listable(MemberRow m, String lowerFilter) {
        if (m.named().startsWith("lambda$") || (m.access() & 0x1000) != 0 || m.name().equals("<clinit>")) return false; // synthetic / static init
        return lowerFilter == null || m.named().toLowerCase(Locale.ROOT).contains(lowerFilter) || m.name().toLowerCase(Locale.ROOT).contains(lowerFilter);
    }

    private static String outlineLine(MemberRow m) {
        String runtime = m.name().equals(m.named()) ? "" : "  [" + m.name() + "]";
        return m.kind().equals("m")
                ? "  " + access(m.access()) + m.named() + Sig.method(m.namedDesc()) + runtime + (m.line() == null ? "" : "  L" + m.line())
                : "  " + access(m.access()) + m.named() + ": " + Sig.field(m.namedDesc()) + runtime;
    }

    /** Superclasses then interfaces, nearest first, within the environment (JDK types are not indexed). */
    List<ClassRow> ancestors(Scope s, ClassRow c) throws SQLException {
        List<ClassRow> out = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>(List.of(c.name()));
        java.util.ArrayDeque<ClassRow> queue = new java.util.ArrayDeque<>(List.of(c));
        while (!queue.isEmpty() && out.size() < 30) {
            ClassRow cur = queue.poll();
            List<String> parents = new ArrayList<>();
            if (cur.superName() != null) parents.add(cur.superName());
            if (cur.interfaces() != null) parents.addAll(List.of(cur.interfaces().split(",")));
            for (String p : parents) {
                if (p.startsWith("java/") || !visited.add(p)) continue;
                List<ClassRow> found = findClasses(s, p, 1);
                if (found.isEmpty()) continue;
                out.add(found.getFirst());
                queue.add(found.getFirst());
            }
        }
        return out;
    }

    private static String access(int a) {
        StringBuilder sb = new StringBuilder();
        if ((a & 0x0008) != 0) sb.append("static ");
        if ((a & 0x0002) != 0) sb.append("private ");
        else if ((a & 0x0004) != 0) sb.append("protected ");
        if ((a & 0x0400) != 0) sb.append("abstract ");
        return sb.toString();
    }

    // ---------------------------------------------------------------- mixins

    public record MixinRow(long artifactId, String config, String mixinClass, String targetClass, String kind, String handler,
                           String targetName, String targetDesc, String atValue, String atTarget, int priority, boolean cancellable,
                           String side, String plugin) {}

    public List<MixinRow> mixinsOn(Scope s, String targetClass, List<String> memberNames) throws SQLException {
        String sql = """
                SELECT artifact_id, config, mixin_class, target_class, kind, handler, target_name, target_desc, at_value, at_target,
                       priority, cancellable, env_side, plugin FROM mixin mx WHERE mx.target_class=? AND %s""".formatted(s.results("mx"));
        List<Object> args = new ArrayList<>(List.of(targetClass));
        if (memberNames != null && !memberNames.isEmpty()) {
            sql += " AND mx.target_name IN (" + String.join(",", memberNames.stream().map(x -> "?").toList()) + ")";
            args.addAll(memberNames);
        }
        sql += " ORDER BY mx.target_name, mx.priority";
        return db.query(sql, rs -> new MixinRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                rs.getInt(12) == 1, rs.getString(13), rs.getString(14)), args.toArray());
    }

    public String mixins(Scope s, String target, boolean includeAccessors, int budget) throws SQLException {
        Target t = Target.parse(target);
        String clsName = t.cls() != null ? t.cls() : target;
        List<ClassRow> classes = findClasses(s, clsName, 5);
        if (classes.isEmpty()) return "No class matching '" + clsName + "'." + past.classes(s, clsName);
        ClassRow c = classes.getFirst();
        List<String> names = null;
        Map<String, MemberRow> byRuntime = new HashMap<>();
        for (MemberRow m : members(c.id())) byRuntime.putIfAbsent(m.name(), m);
        if (t.member() != null) {
            names = new ArrayList<>();
            for (Resolved r : resolveMember(s, c, t.member(), t.desc())) {
                names.add(r.member().name());
                names.add(r.member().named());
            }
            if (names.isEmpty()) names.add(t.member());
        }
        List<MixinRow> rows = mixinsOn(s, c.name(), names);
        Out out = new Out(budget);
        out.force("declared mixins on " + classLine(c) + (t.member() == null ? "" : "." + t.member()) + s.scopeNote() + ": "
                + (rows.size() - countHidden(s, rows)) + " (complete for loaded mods' mixin configs; config plugins/MixinSquared may still disable some at runtime)");
        Map<String, List<MixinRow>> grouped = new TreeMap<>();
        List<MixinRow> classLevel = new ArrayList<>();
        int accessors = 0;
        int otherSide = 0;
        for (MixinRow m : rows) {
            if (s.def().hides(m.side())) otherSide++;
            else if (m.kind().equals("class")) classLevel.add(m);
            else if (!includeAccessors && (m.kind().equals("Accessor") || m.kind().equals("Invoker"))) accessors++;
            else {
                MemberRow mr = byRuntime.get(m.targetName());
                String key = mr != null ? mr.named() + Sig.method(mr.namedDesc()) : m.targetName() + (m.targetDesc() == null ? "" : m.targetDesc());
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
        }
        for (var g : grouped.entrySet()) {
            boolean exclusive = g.getValue().stream().filter(m -> m.kind().equals("Overwrite") || m.kind().equals("Redirect")).count() > 1;
            out.line(g.getKey() + (exclusive ? "   !! multiple Redirect/Overwrite: conflict risk" : ""));
            for (MixinRow m : g.getValue()) out.line("  " + mixinLine(s, m));
        }
        if (!classLevel.isEmpty()) {
            List<String> mods = new ArrayList<>();
            for (MixinRow m : classLevel) mods.add(label(m.artifactId()).split(" ")[0]);
            out.line("class-level only (interfaces/fields/@Unique): " + String.join(", ", mods.stream().distinct().toList()));
        }
        if (accessors > 0) out.line(accessors + " @Accessor/@Invoker (hidden; includeAccessors=true to show)");
        if (otherSide > 0) out.line(otherSide + " client-only mixin(s) hidden (they never apply on this " + s.def().side + ")");
        return out.finish("pass a member (Class.method) to narrow");
    }

    /** Everything the selected mods inject, grouped by target class and method. */
    public String mixinsOfMods(Scope s, int budget) throws SQLException {
        var rows = db.query("""
                SELECT artifact_id, config, mixin_class, target_class, kind, handler, target_name, target_desc, at_value, at_target,
                       priority, cancellable, env_side, plugin FROM mixin mx WHERE %s
                ORDER BY target_class, target_name, priority""".formatted(s.results("mx")),
                rs -> new MixinRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                        rs.getInt(12) == 1, rs.getString(13), rs.getString(14)));
        Out out = new Out(budget);
        int hidden = 0;
        int accessors = 0;
        Map<String, List<MixinRow>> byTarget = new java.util.LinkedHashMap<>();
        for (MixinRow m : rows) {
            if (s.def().hides(m.side())) hidden++;
            else if (m.kind().equals("Accessor") || m.kind().equals("Invoker")) accessors++;
            else byTarget.computeIfAbsent(m.targetClass(), k -> new ArrayList<>()).add(m);
        }
        List<MixinRow> shown = byTarget.values().stream().flatMap(List::stream).toList();
        boolean oneMod = shown.stream().map(m -> {
            try {
                return label(m.artifactId());
            } catch (SQLException ex) {
                return "?";
            }
        }).distinct().count() <= 1;
        boolean allGated = !shown.isEmpty() && shown.stream().allMatch(m -> m.plugin() != null);
        out.force("declared injections by" + s.scopeNote().replace(" [mod: ", " ").replace("]", "")
                + (oneMod && !shown.isEmpty() ? " " + label(shown.getFirst().artifactId()).replaceFirst("^\\S+ ", "") : "") + ": "
                + shown.size() + " into " + byTarget.size() + " classes"
                + (allGated ? " (all gated by a mixin config plugin, so some may be disabled at runtime)" : ""));
        for (var e : byTarget.entrySet()) {
            out.line(Sig.simple(named(e.getKey())) + ":");
            Map<String, String> memberNames = new HashMap<>();
            List<ClassRow> tc = findClasses(s, e.getKey(), 1);
            if (!tc.isEmpty()) for (MemberRow mr : members(tc.getFirst().id())) memberNames.putIfAbsent(mr.name(), mr.named());
            for (MixinRow m : e.getValue()) {
                String method = m.kind().equals("class") ? "" : memberNames.getOrDefault(m.targetName(), m.targetName()) + "  ";
                out.line("  " + method + mixinLine(s, m, !oneMod, !allGated));
            }
        }
        if (accessors > 0) out.line(accessors + " @Accessor/@Invoker not shown");
        if (hidden > 0) out.line(hidden + " client-only injection(s) hidden (they never apply on this " + s.def().side + ")");
        return out.finish("look at one target with mixins <Class.method>");
    }

    private static int countHidden(Scope s, List<MixinRow> rows) {
        return (int) rows.stream().filter(m -> s.def().hides(m.side())).count();
    }

    String mixinLine(Scope s, MixinRow m) throws SQLException {
        return mixinLine(s, m, true, true);
    }

    /** One injection; the mod label and plugin note can be left out when a header already states them. */
    String mixinLine(Scope s, MixinRow m, boolean showMod, boolean showPlugin) throws SQLException {
        StringBuilder sb = new StringBuilder(m.kind().equals("class") ? "(class-level)" : m.kind());
        if (m.atValue() != null) sb.append(" @").append(m.atValue());
        if (m.atTarget() != null) sb.append("(").append(shortTarget(m.atTarget())).append(")");
        if (m.cancellable()) sb.append(" cancellable");
        if (showMod) sb.append("  ").append(label(m.artifactId()));
        if (m.priority() != 1000) sb.append("  prio ").append(m.priority());
        sb.append("  <- ").append(Sig.simple(m.mixinClass()));
        if (m.handler() != null) sb.append("#").append(m.handler());
        if (!"common".equals(m.side())) sb.append("  [").append(m.side()).append("]");
        if (showPlugin && m.plugin() != null) sb.append("  (plugin-gated)");
        sb.append(failedNote(s, m.config(), m.mixinClass()));
        return sb.toString();
    }

    private final Map<String, Map<String, java.time.LocalDateTime>> failedMixins = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();

    /**
     * "  FAILED to apply ..." when the current server's log says this mixin class failed to apply (declared is not
     * applied); "" otherwise and for past snapshots. Reads the log mirror as it is, re-read at most every 2 minutes.
     */
    String failedNote(Scope s, String config, String mixinClass) {
        if (s.historical()) return "";
        Long at = failedAt.get(s.env());
        if (at == null || System.currentTimeMillis() - at > 120_000) {
            failedMixins.put(s.env(), LogService.failedMixins(this.config.home(), s.env()));
            failedAt.put(s.env(), System.currentTimeMillis());
        }
        String cls = mixinClass.replace('/', '.');
        for (var e : failedMixins.get(s.env()).entrySet()) {
            String k = e.getKey(); // config:relative.Class
            int colon = k.indexOf(':');
            if (colon > 0 && k.substring(0, colon).equals(config) && cls.endsWith("." + k.substring(colon + 1))) {
                return "  FAILED to apply on the server (log " + e.getValue().toLocalDate() + "; env filter=errors:" + cls.substring(cls.lastIndexOf('.') + 1) + ")";
            }
        }
        return "";
    }

    /** {@code Lnet/minecraft/class_1297;method_5773()V} -> {@code Entity.tick}. */
    String shortTarget(String at) throws SQLException {
        String owner = null;
        String rest = at;
        if (at.startsWith("L") && at.contains(";")) {
            owner = at.substring(1, at.indexOf(';'));
            rest = at.substring(at.indexOf(';') + 1);
        }
        String name = rest.replaceAll("[(:].*$", "");
        String namedMember = db.queryString("SELECT named FROM member WHERE name=? LIMIT 1", name);
        String ownerName = owner == null ? "" : Sig.simple(named(owner)) + ".";
        return ownerName + (namedMember == null ? name : namedMember);
    }

    // ---------------------------------------------------------------- refs

    public String refs(Scope s, String target, int budget) throws SQLException, IOException {
        Target t = Target.parse(target);
        String clsName = t.cls() != null ? t.cls() : target;
        List<ClassRow> classes = findClasses(s, clsName, 5);
        if (classes.isEmpty()) return "No class matching '" + clsName + "'." + past.classes(s, clsName);
        ClassRow c = classes.getFirst();
        List<ClassRow> users = referencing(s, c.name());
        Out out = new Out(budget);
        if (t.member() == null) return classRefs(s, c, users, out);

        List<Resolved> resolved = resolveMember(s, c, t.member(), t.desc());
        if (resolved.isEmpty()) return "No member '" + t.member() + "' on " + classLine(c) + ".";
        MemberRow target0 = resolved.getFirst().member();
        String owner = resolved.getFirst().owner().name();
        String name = target0.name();
        String desc = t.desc() != null || resolved.size() == 1 ? target0.desc() : null;
        if (!owner.equals(c.name())) users.addAll(referencing(s, owner)); // inherited: calls may name either class as owner
        out.force("call sites of " + memberLine(resolved.getFirst().owner(), target0) + (desc == null ? " (all overloads)" : "") + s.scopeNote());
        int[] sites = {0};
        scan(users, (u, mn, line, o, n, d, callers) -> {
            if (n == null || !n.equals(name) || !(o.equals(owner) || o.equals(c.name())) || (desc != null && !desc.equals(d))) return;
            sites[0]++;
            out.line("  " + Sig.dotted(u.named()) + "." + callerSig(mn, callers) + (line > 0 ? " L" + line : "") + "  | " + label(u.artifactId()));
        });
        if (users.size() > SCAN_LIMIT) out.force("(scanned first " + SCAN_LIMIT + " of " + users.size() + " referencing classes; add mod:<id> to narrow)");
        if (sites[0] == 0) out.force("no direct call sites found");
        overriders(s, resolved.getFirst().owner(), target0, out);
        out.force(coverage(s));
        return out.finish("narrow with a descriptor or mod:<id>");
    }

    private static final int SCAN_LIMIT = 400;

    /** Who uses a class. Few users (or a mod: selection): each class with the members it touches. Many: counts per mod. */
    private String classRefs(Scope s, ClassRow c, List<ClassRow> users, Out out) throws SQLException, IOException {
        out.force(users.size() + " classes reference " + classLine(c) + s.scopeNote());
        if (users.size() > 30 && !s.scoped()) {
            Map<String, Integer> byArtifact = new TreeMap<>();
            for (ClassRow u : users) byArtifact.merge(label(u.artifactId()), 1, Integer::sum);
            byArtifact.forEach((k, v) -> out.line("  " + k + ": " + v));
            out.force(coverage(s));
            return out.finish("add mod:<id> to list the classes and what they use, or pass Class.member for call sites");
        }
        Map<String, String> targetNames = new HashMap<>();
        for (MemberRow m : members(c.id())) targetNames.put(m.name() + m.desc(), m.named());
        Map<ClassRow, Map<String, java.util.Set<String>>> uses = new LinkedHashMap<>(); // class -> caller -> members used
        scan(users, (u, mn, line, o, n, d, callers) -> {
            if (!o.equals(c.name())) return;
            String used = n == null ? "(type)" : targetNames.getOrDefault(n + d, n);
            uses.computeIfAbsent(u, k -> new LinkedHashMap<>())
                    .computeIfAbsent(callerSig(mn, callers), k -> new java.util.TreeSet<>()).add(used);
        });
        for (var e : uses.entrySet()) {
            out.line("  " + Sig.dotted(e.getKey().named()) + "  | " + label(e.getKey().artifactId()));
            for (var caller : e.getValue().entrySet()) out.line("    " + caller.getKey() + " uses " + String.join(", ", caller.getValue()));
        }
        out.force(coverage(s));
        return out.finish("pass Class.member for exact call sites");
    }

    private List<ClassRow> referencing(Scope s, String owner) throws SQLException {
        return db.query("""
                SELECT c.id, c.name, c.named, c.artifact_id, c.super, c.interfaces, c.access
                FROM class_ref r JOIN class c ON c.id=r.class_id WHERE r.owner=? AND %s""".formatted(s.results("c")),
                rs -> new ClassRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)),
                owner);
    }

    /** One line stating what an answer covers, so a complete answer can be trusted without re-checking it by hand. */
    String coverage(Scope s) throws SQLException {
        String what = s.scoped() ? "the selected mods (" + s.onlyLabel() + ")"
                : "all " + db.queryInt("SELECT count(*) FROM (" + s.artifactSet() + ")")
                + " loaded artifacts (Minecraft, mods, nested jars)";
        return "coverage: bytecode of " + what + "; not included: reflection, runtime method handles, calls injected by mixins";
    }

    @FunctionalInterface
    private interface Hit {
        /** {@code name}/{@code desc} are null for type-only uses (new, instanceof, casts, class literals). */
        void on(ClassRow user, MethodNode caller, int line, String owner, String name, String desc, Map<String, MemberRow> callerMembers)
                throws SQLException;
    }

    /** Reads the bytecode of up to {@link #SCAN_LIMIT} referencing classes and reports every reference instruction. */
    private void scan(List<ClassRow> users, Hit hit) throws SQLException, IOException {
        Map<Long, List<ClassRow>> byArtifact = new LinkedHashMap<>();
        for (ClassRow u : users.subList(0, Math.min(users.size(), SCAN_LIMIT))) byArtifact.computeIfAbsent(u.artifactId(), k -> new ArrayList<>()).add(u);
        for (var e : byArtifact.entrySet()) {
            String sha = db.queryString("SELECT sha256 FROM artifact WHERE id=?", e.getKey());
            Path jar = config.home().resolve("artifacts").resolve(sha.substring(0, 2)).resolve(sha + ".jar");
            if (!Files.exists(jar)) continue;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ClassRow u : e.getValue()) {
                    ZipEntry ze = zip.getEntry(u.name() + ".class");
                    if (ze == null) continue;
                    ClassNode node = new ClassNode();
                    new ClassReader(zip.getInputStream(ze).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
                    Map<String, MemberRow> callers = new HashMap<>();
                    for (MemberRow m : members(u.id())) callers.put(m.name() + m.desc(), m);
                    for (MethodNode mn : node.methods) {
                        int line = -1;
                        for (AbstractInsnNode insn : mn.instructions) {
                            switch (insn) {
                                case LineNumberNode ln -> line = ln.line;
                                case MethodInsnNode mi -> hit.on(u, mn, line, mi.owner, mi.name, mi.desc, callers);
                                case FieldInsnNode fi -> hit.on(u, mn, line, fi.owner, fi.name, fi.desc, callers);
                                case org.objectweb.asm.tree.TypeInsnNode ti -> hit.on(u, mn, line, ti.desc, null, null, callers);
                                case org.objectweb.asm.tree.LdcInsnNode ldc when ldc.cst instanceof org.objectweb.asm.Type ty
                                        && ty.getSort() == org.objectweb.asm.Type.OBJECT -> hit.on(u, mn, line, ty.getInternalName(), null, null, callers);
                                default -> { }
                            }
                        }
                    }
                }
            }
        }
    }

    /** {@code onServerTick(MinecraftServer)}: the caller's display name with its parameter types. */
    private static String callerSig(MethodNode mn, Map<String, MemberRow> callers) {
        MemberRow m = callers.get(mn.name + mn.desc);
        String name = m == null ? mn.name : m.named();
        String sig = Sig.method(m == null ? mn.desc : m.namedDesc());
        int arrow = sig.indexOf(" -> ");
        return name + (arrow > 0 ? sig.substring(0, arrow) : sig);
    }

    private void overriders(Scope s, ClassRow owner, MemberRow m, Out out) throws SQLException {
        if (!m.kind().equals("m") || m.name().startsWith("<")) return;
        List<String> found = new ArrayList<>();
        List<String> frontier = new ArrayList<>(List.of(owner.name()));
        for (int depth = 0; depth < 6 && !frontier.isEmpty() && found.size() < 30; depth++) {
            List<String> next = new ArrayList<>();
            for (String sup : frontier) {
                var subs = db.query("SELECT c.id, c.name, c.named, c.artifact_id FROM class c WHERE c.super=? AND " + s.artifacts("c") + " LIMIT 200",
                        rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)}, sup);
                for (Object[] sub : subs) {
                    next.add((String) sub[1]);
                    if (db.queryInt("SELECT count(*) FROM member WHERE class_id=? AND name=? AND descriptor=?", sub[0], m.name(), m.desc()) > 0) {
                        found.add(Sig.simple((String) sub[2]) + " (" + label((long) sub[3]).split(" ")[0] + ")");
                    }
                }
            }
            frontier = next;
        }
        if (!found.isEmpty()) out.line("overridden in: " + String.join(", ", found));
    }
}
