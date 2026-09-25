package dev.envx.query;

import dev.envx.env.Environments;
import dev.envx.index.ParsedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code env filter=diff:A..B}: what changed between two pack versions and what it means for the project in the
 * working directory. For the project: mixin targets that stopped resolving, other mods' injections that appeared or
 * left at the project's injection points, calls into other mods' code that no longer resolve (a NoSuchMethodError on
 * the next start), changed dependencies and the project's own configs. For the pack: mod and config changes, and when
 * B is the current server, what its logs show since then.
 */
final class UpdateReport {
    /** Owners that do not change with a pack version (same Minecraft) or are never another mod's API. */
    private static final List<String> STABLE = List.of("java/", "javax/", "jdk/", "sun/", "net/minecraft/", "com/mojang/",
            "org/spongepowered/", "com/llamalad7/", "org/objectweb/", "org/slf4j/", "org/apache/", "com/google/",
            "it/unimi/", "io/netty/", "org/joml/", "org/jetbrains/", "kotlin/");
    private static final int MAX_REFS = 3000;

    private final QueryService q;

    UpdateReport(QueryService q) {
        this.q = q;
    }

    String report(Scope s, String spec, Path cwd, int budget) throws SQLException, IOException {
        String[] ab = spec.split("\\.\\.", -1);
        Environments envs = new Environments(q.config(), q.db());
        String from = ab[0].isBlank() ? null : ab[0].trim();
        String to = ab.length > 1 && !ab[1].isBlank() ? ab[1].trim() : "current";
        if (from == null) throw new IllegalArgumentException("diff needs a version, e.g. diff:4.0.5..4.1.0. Known: " + envs.labels(s.env()));
        long a = envs.resolve(s.env(), from);
        long b = envs.resolve(s.env(), to);
        Scope sa = Scope.of(q.config(), q.db(), s.env(), a);
        Scope sb = Scope.of(q.config(), q.db(), s.env(), b);

        Out out = new Out(budget);
        out.force("# " + s.env() + " " + from + " (snapshot " + a + ") -> " + to + " (snapshot " + b + ")");
        Map<String, List<String>> mods = sections(envs.diff(a, b));
        out.force(mods.isEmpty() ? "mods: no changes" : "mods: " + String.join(", ", mods.entrySet().stream()
                .map(e -> e.getValue().size() + " " + e.getKey().toLowerCase(Locale.ROOT)).toList()) + " (listed below)");

        Project p = Project.find(q.config(), cwd);
        if (p != null && p.modId() != null) project(sa, sb, p, out);

        for (var e : mods.entrySet()) {
            out.line("## " + e.getKey() + " (" + e.getValue().size() + ")");
            for (String m : e.getValue()) out.line("- " + m, e.getKey(), m.split(" ")[0]);
        }
        configs(sa, sb, p, out);
        if (!sb.historical() && "sync".equals(q.db().queryString("SELECT kind FROM snapshot WHERE id=?", b))) {
            LocalDateTime since = LocalDateTime.ofInstant(Instant.parse(sb.takenAt()), ZoneId.systemDefault());
            for (String l : new LogService(q).since(sb, since, p == null ? null : p.modId())) out.line(l);
        }
        return out.finish("env=" + s.env() + "@" + from + " queries the old version; check_mixins validates the project against the current one");
    }

    /** "Added" -> entries, from {@link Environments#diff}'s markdown. */
    private static Map<String, List<String>> sections(String diff) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> cur = null;
        for (String l : diff.split("\n")) {
            if (l.startsWith("## ")) out.put(l.substring(3).replaceFirst(" \\(\\d+\\)$", ""), cur = new ArrayList<>());
            else if (l.startsWith("- ") && cur != null) cur.add(l.substring(2));
        }
        return out;
    }

    // ---------------------------------------------------------------- project

    private void project(Scope sa, Scope sb, Project p, Out out) throws SQLException, IOException {
        out.force("## Project " + p.modId() + " " + p.version() + " (" + p.dir().getFileName() + ")");
        if (p.jar() == null) {
            out.line("not built: mixins and calls into other mods are checked on the built jar (gradlew build)");
        } else {
            MixinCheck check = new MixinCheck(q);
            ParsedJar pj = check.parse(sb, p.jar());
            out.line("checked " + p.jar().getFileName() + (p.jarStale() ? " (STALE: sources changed after this build)" : ""));
            mixins(check, pj, sa, sb, out);
            calls(p, pj, sa, sb, out);
        }
        dependencies(sa, sb, p, out);
    }

    private void mixins(MixinCheck check, ParsedJar pj, Scope sa, Scope sb, Out out) throws SQLException {
        if (pj.mixins.isEmpty()) return;
        List<MixinCheck.Point> before = check.evaluate(sa, pj);
        List<MixinCheck.Point> after = check.evaluate(sb, pj);
        List<String> broke = new ArrayList<>(), fixed = new ArrayList<>(), added = new ArrayList<>(), gone = new ArrayList<>();
        int stillBroken = 0;
        for (int i = 0; i < after.size(); i++) {
            MixinCheck.Point x = before.get(i), y = after.get(i);
            String cls = Sig.simple(y.mixinClass());
            if (y.error() != null && x.error() == null) broke.add("  now ERROR " + cls + " " + y.error() + (x.ok() == null ? "" : " (was ok: " + x.ok().replaceFirst("  .*$", "") + ")"));
            else if (y.error() != null) stillBroken++;
            else if (x.error() != null) fixed.add("  now ok " + cls + " " + y.ok());
            else if (y.warn() != null && x.warn() == null) broke.add("  now WARN " + cls + " " + y.warn());
            if (y.ok() == null) continue;
            String at = y.ok().replaceFirst("^#\\S+ -> ", "").replaceFirst("  .*$", "");
            for (var r : y.rivals().entrySet()) {
                if (!x.rivals().containsKey(r.getKey())) added.add("  " + at + ": " + r.getValue().trim());
            }
            for (var r : x.rivals().entrySet()) {
                if (!y.rivals().containsKey(r.getKey())) gone.add("  " + at + ": " + r.getValue().trim().replaceFirst("  FAILED to apply.*$", ""));
            }
        }
        // Say outright when every point resolves in both versions: agents otherwise re-run check_mixins to confirm it.
        String state = broke.isEmpty() && stillBroken == 0 && fixed.isEmpty()
                ? "all resolve in " + label(sa) + " and " + label(sb) + " (no need to re-run check_mixins)"
                : (broke.isEmpty() ? "none newly broken" : broke.size() + " newly broken")
                  + (stillBroken > 0 ? ", " + stillBroken + " broken in both" : "") + (fixed.isEmpty() ? "" : ", " + fixed.size() + " fixed");
        out.line("mixins (" + after.size() + " injection points): " + state
                + "; other mods' injections at them: " + dedupe(added).size() + " new, " + dedupe(gone).size() + " gone");
        for (String l : broke) out.line(l);
        for (String l : fixed) out.line(l);
        if (!added.isEmpty()) out.line(" new:");
        for (String l : dedupe(added)) out.line(" " + l);
        if (!gone.isEmpty()) out.line(" gone:");
        for (String l : dedupe(gone)) out.line(" " + l);
    }

    /** "4.0.5", "current" or "#12": how a snapshot is named in answers. */
    private static String label(Scope s) {
        return s.at() == null ? "current" : s.at();
    }

    private static List<String> dedupe(List<String> l) {
        return List.copyOf(new java.util.LinkedHashSet<>(l));
    }

    private record Ref(String owner, String name, String desc) {}

    /** Calls and field accesses into other mods' code that resolve in A but not in B (NoSuchMethodError/NoClassDefFoundError). */
    private void calls(Project p, ParsedJar pj, Scope sa, Scope sb, Out out) throws SQLException, IOException {
        Set<String> own = new HashSet<>();
        for (ParsedJar.ClassRec c : pj.classes) own.add(c.name());
        Map<Ref, List<String>> refs = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(p.jar()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(zip.readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode insn : m.instructions) {
                        Ref r = switch (insn) {
                            case MethodInsnNode mi -> new Ref(mi.owner, mi.name, mi.desc);
                            case FieldInsnNode fi -> new Ref(fi.owner, fi.name, fi.desc);
                            default -> null;
                        };
                        if (r == null || own.contains(r.owner()) || r.owner().startsWith("[") || STABLE.stream().anyMatch(r.owner()::startsWith)) continue;
                        if (refs.size() >= MAX_REFS && !refs.containsKey(r)) continue;
                        List<String> from = refs.computeIfAbsent(r, k -> new ArrayList<>());
                        String caller = Sig.simple(cn.name) + "." + m.name;
                        if (!from.contains(caller)) from.add(caller);
                    }
                }
            }
        }
        if (refs.isEmpty()) return;
        Map<String, String> modOfClass = new HashMap<>();
        Set<String> mods = new TreeSet<>();
        List<String> broken = new ArrayList<>();
        for (var e : refs.entrySet()) {
            Ref r = e.getKey();
            List<QueryService.ClassRow> ca = q.findClasses(sa, r.owner(), 1);
            if (ca.isEmpty()) continue; // not on the server in A either (compile-only or optional)
            String mod = modOfClass.computeIfAbsent(r.owner(), k -> {
                try {
                    return q.label(ca.getFirst().artifactId());
                } catch (SQLException ex) {
                    return "?";
                }
            });
            if (q.resolveMember(sa, ca.getFirst(), r.name(), r.desc()).isEmpty()) continue;
            mods.add(mod.split(" ")[0]);
            List<QueryService.ClassRow> cb = q.findClasses(sb, r.owner(), 1);
            String why = cb.isEmpty() ? "class gone" : q.resolveMember(sb, cb.getFirst(), r.name(), r.desc()).isEmpty() ? "member gone" : null;
            if (why == null) continue;
            String now = cb.isEmpty() ? nowOf(sb, mod.split(" ")[0]) : q.label(cb.getFirst().artifactId());
            broken.add("  " + why + ": " + Sig.simple(r.owner()) + "." + r.name() + (r.desc().startsWith("(") ? Sig.method(r.desc()) : "")
                    + "  [" + mod + " -> " + now + "]  used by " + String.join(", ", e.getValue().subList(0, Math.min(3, e.getValue().size())))
                    + (e.getValue().size() > 3 ? " +" + (e.getValue().size() - 3) : ""));
        }
        if (mods.isEmpty()) return;
        out.line("calls into other mods (" + mods.size() + " mods: " + String.join(", ", mods) + "): "
                + (broken.isEmpty() ? "all still resolve" : broken.size() + " no longer resolve (fails at runtime when reached)"));
        for (String l : broken) out.line(l);
    }

    private String nowOf(Scope s, String modId) throws SQLException {
        String v = q.db().queryString("""
                SELECT a.mod_version FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND sa.loaded=1 AND a.mod_id=? LIMIT 1""", s.snapshotId(), modId);
        return v == null ? "removed" : modId + " " + v;
    }

    private void dependencies(Scope sa, Scope sb, Project p, Out out) throws SQLException {
        ProjectReport pr = new ProjectReport(q);
        List<String> changed = new ArrayList<>();
        List<String> same = new ArrayList<>();
        Map<String, Map<String, String>> kinds = new LinkedHashMap<>();
        kinds.put("depends", p.depends());
        kinds.put("suggests", p.optional());
        kinds.put("breaks", p.breaks());
        for (var k : kinds.entrySet()) {
            for (var e : k.getValue().entrySet()) {
                String va = pr.serverVersion(sa, e.getKey()), vb = pr.serverVersion(sb, e.getKey());
                if (va == null ? vb == null : va.equals(vb)) {
                    same.add(e.getKey() + " " + (va == null ? "(not on server)" : va));
                    continue;
                }
                String what = vb == null ? "removed (was " + va + ")" : va == null ? "added " + vb : va + " -> " + vb;
                String status = "";
                if (vb != null && k.getKey().equals("depends")) {
                    Boolean ok = ProjectReport.VersionRange.satisfies(vb, e.getValue());
                    status = ok == null ? "" : ok ? ", " + e.getValue() + " ok" : ", NOT satisfied: needs " + e.getValue();
                } else if (vb == null && k.getKey().equals("depends")) {
                    status = ", REQUIRED: the mod will not load";
                } else if (vb != null && k.getKey().equals("breaks")) {
                    status = ", declared incompatible (" + e.getValue() + ")";
                }
                changed.add(e.getKey() + " " + what + " (" + k.getKey() + status + ")");
            }
        }
        if (changed.isEmpty() && same.isEmpty()) return;
        // Named, not counted: "4 unchanged" hid the dependency a question was about.
        out.line("dependencies: " + (changed.isEmpty() ? "" : String.join("; ", changed) + "; ")
                + (same.isEmpty() ? "" : "unchanged: " + String.join(", ", same)));
    }

    // ---------------------------------------------------------------- configs

    private void configs(Scope sa, Scope sb, Project p, Out out) throws SQLException, IOException {
        Map<String, String> ta = texts(sa.snapshotId()), tb = texts(sb.snapshotId());
        List<String> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
        for (var e : tb.entrySet()) {
            String old = ta.get(e.getKey());
            if (old == null) added.add(e.getKey());
            else if (!old.equals(e.getValue())) changed.add(e.getKey());
        }
        for (String k : ta.keySet()) if (!tb.containsKey(k)) removed.add(k);
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            out.line("configs and datapacks: no changes");
            return;
        }
        out.line("configs and datapacks: " + changed.size() + " changed, " + added.size() + " added, " + removed.size() + " removed");
        Environments envs = new Environments(q.config(), q.db());
        List<String> mine = new ArrayList<>();
        if (p != null && p.modId() != null) {
            String id = p.modId().toLowerCase(Locale.ROOT);
            for (String k : changed) if (k.toLowerCase(Locale.ROOT).contains(id)) mine.add(k);
        }
        for (String k : mine) {
            out.line("  " + k + " (this project's config):");
            for (String l : lineDiff(Files.readAllLines(envs.textPath(ta.get(k))), Files.readAllLines(envs.textPath(tb.get(k))), 8)) out.line("    " + l);
        }
        list(out, "changed", changed.stream().filter(k -> !mine.contains(k)).toList());
        list(out, "added", added);
        list(out, "removed", removed);
    }

    /** Paths grouped by their folder under config/ (usually one mod): "bhmenu (3), bcc.json, defaultconfigs/x.toml". */
    private static void list(Out out, String what, List<String> paths) {
        if (paths.isEmpty()) return;
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (String p : paths) {
            String rest = p.startsWith("config/") ? p.substring(7) : p;
            groups.merge(rest.contains("/") && !p.equals(rest) ? rest.substring(0, rest.indexOf('/')) : rest, 1, Integer::sum);
        }
        List<String> parts = groups.entrySet().stream().map(e -> e.getKey() + (e.getValue() > 1 ? " (" + e.getValue() + ")" : "")).toList();
        int n = Math.min(20, parts.size());
        out.line("  " + what + ": " + String.join(", ", parts.subList(0, n)) + (parts.size() > n ? " +" + (parts.size() - n) + " more" : ""));
    }

    private Map<String, String> texts(long snapshot) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        for (String[] r : q.db().query("SELECT rel_path, sha256 FROM snapshot_text WHERE snapshot_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, snapshot)) {
            out.put(r[0], r[1]);
        }
        return out;
    }

    /** Removed and added lines (not a minimal diff: lines present on one side only, in file order), at most {@code max} each. */
    static List<String> lineDiff(List<String> a, List<String> b, int max) {
        Map<String, Integer> countB = new HashMap<>(), countA = new HashMap<>();
        for (String l : b) countB.merge(l.strip(), 1, Integer::sum);
        for (String l : a) countA.merge(l.strip(), 1, Integer::sum);
        List<String> minus = new ArrayList<>(), plus = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (String l : a) {
            String k = l.strip();
            if (k.isEmpty()) continue;
            if (seen.merge(k, 1, Integer::sum) > countB.getOrDefault(k, 0)) minus.add(k);
        }
        seen.clear();
        for (String l : b) {
            String k = l.strip();
            if (k.isEmpty()) continue;
            if (seen.merge(k, 1, Integer::sum) > countA.getOrDefault(k, 0)) plus.add(k);
        }
        List<String> out = new ArrayList<>();
        for (String l : minus.subList(0, Math.min(max, minus.size()))) out.add("- " + clip(l));
        if (minus.size() > max) out.add("- ... +" + (minus.size() - max) + " more");
        for (String l : plus.subList(0, Math.min(max, plus.size()))) out.add("+ " + clip(l));
        if (plus.size() > max) out.add("+ ... +" + (plus.size() - max) + " more");
        return out;
    }

    private static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
