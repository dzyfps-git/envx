package dev.sevli.query;

import dev.sevli.fabric.FabricBase;
import dev.sevli.index.JarParser;
import dev.sevli.index.ParsedJar;
import dev.sevli.mapping.Mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Validates a project's mixins against an environment without building a server: every target
 * class, target method and {@code @At} target must exist in the pack, and every other mod that
 * injects into the same method is listed. Works on the project's built (remapped) jar, which has
 * runtime names, so it compares like with like.
 */
public final class MixinCheck {
    private final QueryService q;
    private Mappings mappings;

    public MixinCheck(QueryService q) {
        this.q = q;
    }

    public String check(Scope s, Path projectDir, int budget) throws IOException, SQLException {
        if (q.config().isDenied(projectDir)) return "Not read: " + projectDir + " is inside a denied root (config.json denyRoots).";
        Project project = Project.find(q.config(), projectDir);
        Path jar = project != null && project.dir().equals(projectDir.toAbsolutePath().normalize()) ? project.jar() : Project.builtJar(projectDir, null);
        if (jar == null) return "No built jar in " + projectDir.resolve("build/libs") + ". Build the project first (gradlew build).";
        Out out = new Out(budget);
        FileTime jarTime = Files.getLastModifiedTime(jar);
        FileTime newestSrc = newest(projectDir.resolve("src"));
        String stale = newestSrc != null && newestSrc.compareTo(jarTime) > 0 ? "  (STALE: sources changed after this build)" : "";
        ParsedJar pj = parse(s, jar);
        out.force("check_mixins " + jar.getFileName() + stale + " against " + s.env() + " (snapshot " + s.snapshotId() + ")");
        if (pj.mixins.isEmpty()) return out.finish(null) + "no mixins declared\n";

        int errors = 0;
        int warnings = 0;
        int contested = 0;
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        Map<String, List<String>> previous = new LinkedHashMap<>();
        Map<String, List<String>> quiet = new LinkedHashMap<>();
        for (Point p : evaluate(s, pj)) {
            List<String> lines = byClass.computeIfAbsent(p.mixinClass(), k -> new ArrayList<>());
            if (p.error() != null) {
                errors++;
                lines.add("  ERROR " + p.error());
                continue;
            }
            if (p.warn() != null) {
                warnings++;
                lines.add("  WARN  " + p.warn());
            }
            if (p.ok() == null) continue;
            List<String> rivals = List.copyOf(p.rivals().values());
            if (rivals.isEmpty() && p.warn() == null && p.note().isEmpty()) { // healthy and unshared: one line per class
                quiet.computeIfAbsent(p.mixinClass(), k -> new ArrayList<>()).add(p.ok().replaceFirst("  .*$", ""));
                previous.put(p.mixinClass(), rivals);
                continue;
            }
            boolean same = !rivals.isEmpty() && rivals.equals(previous.getOrDefault(p.mixinClass(), List.of()));
            lines.add("  ok    " + p.ok() + (rivals.isEmpty() ? "" : same ? "  (the same " + rivals.size() + " other mixin(s) as above)"
                    : "  (" + rivals.size() + " other mixin(s) here)") + p.note());
            if (!rivals.isEmpty()) contested++;
            if (!same) lines.addAll(shown(rivals, p.ok()));
            previous.put(p.mixinClass(), rivals);
        }
        // Classes with errors, then warnings, then shared points, then the rest: a cut answer never hides a problem.
        List<Map.Entry<String, List<String>>> classes = new ArrayList<>(byClass.entrySet());
        classes.sort(Comparator.comparingInt(e -> e.getValue().stream().anyMatch(l -> l.startsWith("  ERROR")) ? 0
                : e.getValue().stream().anyMatch(l -> l.startsWith("  WARN")) ? 1 : e.getValue().isEmpty() ? 3 : 2));
        List<String> notShown = new ArrayList<>(), notShownShared = new ArrayList<>();
        for (var e : classes) {
            List<String> ok = quiet.getOrDefault(e.getKey(), List.of());
            if (e.getValue().isEmpty() && ok.isEmpty()) continue;
            String name = Sig.simple(e.getKey());
            List<String> block = new ArrayList<>(List.of(name + ":"));
            block.addAll(e.getValue());
            if (!ok.isEmpty()) block.add("  ok    " + String.join("; ", ok) + (e.getValue().isEmpty() ? "" : "  (no other mods here)"));
            int size = block.stream().mapToInt(l -> l.length() + 1).sum();
            if (size <= out.remaining() - 700) block.forEach(out::line); // room kept for the summary
            else (e.getValue().isEmpty() ? notShown : notShownShared).add(name);
        }
        if (!notShown.isEmpty() || !notShownShared.isEmpty()) { // only classes without errors or warnings are ever left out
            out.force("not shown, no errors or warnings: " + (notShown.isEmpty() ? "" : notShown.size() + " all ok, no other mods ("
                    + String.join(", ", notShown) + ")") + (notShown.isEmpty() || notShownShared.isEmpty() ? "" : "; ")
                    + (notShownShared.isEmpty() ? "" : notShownShared.size() + " sharing methods with other mods (" + String.join(", ", notShownShared)
                    + "; mixins <Class.method> lists them)"));
        }
        out.force("summary: " + pj.mixins.size() + " injection points, " + errors + " error(s), " + warnings + " warning(s), "
                + contested + " shared with other mods (declared; runtime order/plugins not verified)");
        return out.finish("errors are listed first per class; fix and rebuild");
    }

    /** Rivals listed in full per injection point; a busy method (LivingEntity.damage has ~100) must not hide the rest. */
    static final int RIVALS_SHOWN = 3;
    private static final java.util.regex.Pattern RIVAL_MOD = java.util.regex.Pattern.compile("  (\\S+) \\S+(?:  prio -?\\d+)?  <- ");

    /**
     * Up to {@link #RIVALS_SHOWN} in full: conflicts first, then cancellable injections (they can skip the project's
     * code). The rest are named by mod only, with the query that lists them all.
     */
    static List<String> shown(List<String> rivals, String ok) {
        if (rivals.size() <= RIVALS_SHOWN) return rivals;
        List<String> ranked = new ArrayList<>(rivals);
        ranked.sort(Comparator.comparingInt((String r) -> r.contains("CONFLICT") ? 0 : r.contains(" cancellable") ? 1 : 2));
        List<String> out = new ArrayList<>(ranked.subList(0, RIVALS_SHOWN));
        java.util.Set<String> mods = new java.util.LinkedHashSet<>();
        for (String r : ranked.subList(RIVALS_SHOWN, ranked.size())) {
            java.util.regex.Matcher m = RIVAL_MOD.matcher(r);
            if (m.find()) mods.add(m.group(1));
        }
        List<String> named = mods.stream().limit(8).toList();
        String target = ok.replaceFirst("^#\\S+ -> ", "").replaceFirst("  .*$", "");
        out.add("    +" + (rivals.size() - RIVALS_SHOWN) + " more from " + String.join(", ", named)
                + (mods.size() > named.size() ? " +" + (mods.size() - named.size()) + " mods" : "") + " (mixins " + target + " lists all)");
        return out;
    }

    /** The project's built jar, read with runtime names (it is remapped at build time) and Yarn for display. */
    ParsedJar parse(Scope s, Path jar) throws IOException {
        if (mappings == null) mappings = FabricBase.forEnv(q.config().home(), s.def().minecraft, s.def().mappings).loadMappings();
        return new JarParser(mappings).parse(jar.getFileName().toString(), Files.readAllBytes(jar), "mod");
    }

    /**
     * One injection point checked against a scope. {@code error}/{@code warn} are problem texts; {@code ok} is
     * "#handler -> Target.member  kind..." when the target resolved (null for class-level mixins); {@code rivals} are
     * other mods' injections at the same member, keyed by mod id, mixin class and handler (version-independent).
     */
    record Point(String mixinClass, String error, String warn, String ok, String note, Map<String, String> rivals) {}

    /** Checks every injection point of {@code pj} against {@code s}, in declaration order. */
    List<Point> evaluate(Scope s, ParsedJar pj) throws SQLException {
        String modId = pj.mod == null ? null : pj.mod.id();
        List<Point> out = new ArrayList<>();
        for (ParsedJar.MixinRec m : pj.mixins) {
            List<QueryService.ClassRow> target = q.findClasses(s, m.targetClass(), 1);
            if (target.isEmpty()) {
                out.add(new Point(m.mixinClass(), "target class " + Sig.dotted(mappings.cls(m.targetClass())) + " is not in the environment", null, null, "", Map.of()));
                continue;
            }
            QueryService.ClassRow tc = target.getFirst();
            if (m.kind().equals("class") || m.targetName() == null || m.targetName().equals("?")) {
                out.add(new Point(m.mixinClass(), null, null, null, "", Map.of()));
                continue;
            }
            String handler = "#" + m.handler() + " " + m.kind();
            List<QueryService.MemberRow> hits = matchTarget(tc, m);
            if (hits.isEmpty()) {
                out.add(new Point(m.mixinClass(), handler + ": no member '" + mappings.member(m.targetName()) + (m.targetDesc() == null ? "" : m.targetDesc())
                        + "' on " + Sig.simple(tc.named()) + suggestions(tc, m.targetName()), null, null, "", Map.of()));
                continue;
            }
            QueryService.MemberRow hit = hits.getFirst();
            String targetLabel = Sig.simple(tc.named()) + "." + hit.named();
            String warn = m.atTarget() != null && m.atTarget().contains(";") && !atTargetExists(s, m.atTarget())
                    ? handler + " on " + targetLabel + ": @At target not found in environment: " + q.shortTarget(m.atTarget()) : null;
            Map<String, String> rivals = new LinkedHashMap<>(); // a mod can declare the same mixin twice
            for (QueryService.MixinRow other : q.mixinsOn(s, tc.name(), List.of(hit.name(), hit.named()))) {
                String otherMod = q.db().queryString("SELECT mod_id FROM artifact WHERE id=?", other.artifactId());
                if (modId != null && modId.equals(otherMod)) continue; // the deployed copy of this same mod
                if (other.kind().equals("Accessor") || other.kind().equals("Invoker") || s.def().hides(other.side())) continue;
                boolean clash = isExclusive(m.kind()) && isExclusive(other.kind())
                        && (m.atTarget() == null || m.atTarget().equals(other.atTarget()));
                rivals.putIfAbsent(otherMod + "|" + other.mixinClass() + "|" + other.handler() + "|" + other.kind(),
                        (clash ? "    CONFLICT " : "    also: ") + q.mixinLine(s, other));
            }
            String own = m.kind() + (m.atValue() == null ? "" : " @" + m.atValue()) + (m.cancellable() ? " cancellable" : "")
                    + (m.priority() != 1000 ? " prio " + m.priority() : "");
            out.add(new Point(m.mixinClass(), null, warn, handler.replace(" " + m.kind(), "") + " -> " + targetLabel + "  " + own,
                    q.failedNote(s, m.config(), m.mixinClass()), rivals));
        }
        return out;
    }

    private List<QueryService.MemberRow> matchTarget(QueryService.ClassRow tc, ParsedJar.MixinRec m) throws SQLException {
        String name = m.targetName();
        String wantKind = m.kind().equals("Accessor") ? "f" : "m"; // every other selector names a method
        List<QueryService.MemberRow> out = new ArrayList<>();
        for (QueryService.MemberRow r : q.members(tc.id())) {
            if (!r.kind().equals(wantKind)) continue;
            boolean nameOk = name.endsWith("*") ? r.name().startsWith(name.substring(0, name.length() - 1)) || r.named().startsWith(name.substring(0, name.length() - 1))
                    : r.name().equals(name) || r.named().equals(name);
            if (!nameOk) continue;
            if (m.targetDesc() != null && !m.targetDesc().equals(r.desc()) && !m.targetDesc().equals(r.namedDesc())) continue;
            out.add(r);
        }
        return out;
    }

    private boolean atTargetExists(Scope s, String at) throws SQLException {
        String owner = at.substring(1, at.indexOf(';'));
        String rest = at.substring(at.indexOf(';') + 1);
        String name = rest.replaceAll("[(:].*$", "");
        String desc = rest.contains("(") ? rest.substring(rest.indexOf('(')) : rest.contains(":") ? rest.substring(rest.indexOf(':') + 1) : null;
        if (owner.startsWith("java/")) return true; // JDK targets are not indexed
        List<QueryService.ClassRow> cls = q.findClasses(s, owner, 1);
        if (cls.isEmpty()) return false;
        return !q.resolveMember(s, cls.getFirst(), name, desc).isEmpty();
    }

    private String suggestions(QueryService.ClassRow tc, String name) throws SQLException {
        String n = mappings.member(name).toLowerCase(Locale.ROOT);
        String stem = n.length() > 4 ? n.substring(0, 4) : n;
        List<String> near = new ArrayList<>();
        for (QueryService.MemberRow r : q.members(tc.id())) {
            if (r.named().toLowerCase(Locale.ROOT).contains(stem) && near.size() < 5) near.add(r.named() + Sig.method(r.namedDesc()));
        }
        return near.isEmpty() ? "" : " (similar: " + String.join(", ", near) + ")";
    }

    private static boolean isExclusive(String kind) {
        return kind.equals("Redirect") || kind.equals("Overwrite") || kind.equals("ModifyConstant");
    }

    private static FileTime newest(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.toFile().lastModified()).max(Long::compare).map(FileTime::fromMillis).orElse(null);
        }
    }
}
