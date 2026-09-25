package dev.envx.query;

import dev.envx.fabric.FabricBase;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Readable source on demand. Nothing is decompiled up front: the first request for a class
 * decompiles that one class (with its inner classes) and caches the result on disk forever, keyed
 * by jar hash + mappings + decompiler version.
 *
 * <p>Minecraft classes come from Loom's Yarn-named jar. Mod jars are intermediary at runtime, so
 * they are first remapped to Yarn once per jar (cached), then decompiled class by class.
 */
public final class SourceService {
    private static final String DECOMPILER = "vf1.12";

    private final QueryService q;
    private final Path home;

    public SourceService(QueryService q) {
        this.q = q;
        this.home = q.config().home();
    }

    public String source(Scope s, String target, String lines, int budget) throws SQLException, IOException {
        Target t = Target.parse(target);
        String clsName = t.cls() != null ? t.cls() : target;
        List<QueryService.ClassRow> classes = q.findResultClasses(s, clsName, 5);
        if (classes.isEmpty()) return "No class matching '" + clsName + "'" + s.scopeNote() + "." + q.pastClasses(s, clsName);
        QueryService.ClassRow c = classes.getFirst();
        FabricBase base = FabricBase.forEnv(home, s.def().minecraft, s.def().mappings);

        String runtimeOuter = outer(c.name());
        String namedOuter = outer(c.named());
        String kind = q.db().queryString("SELECT kind FROM artifact WHERE id=?", c.artifactId());
        String sha = q.db().queryString("SELECT sha256 FROM artifact WHERE id=?", c.artifactId());
        Path sourceJar;
        String className;
        String cacheKey;
        if ("minecraft".equals(kind)) {
            sourceJar = base.namedJar();
            className = namedOuter;
            cacheKey = base.id() + "-" + DECOMPILER;
        } else {
            sourceJar = remapped(sha, base);
            className = runtimeOuter; // mod classes keep their own names
            cacheKey = sha.substring(0, 16) + "-" + base.id() + "-" + DECOMPILER;
        }
        Path cached = home.resolve("decomp").resolve(cacheKey).resolve(className + ".java");
        if (!Files.exists(cached)) decompile(sourceJar, className, "minecraft".equals(kind) ? List.of() : List.of(base.namedJar()), cached);
        List<String> text = Files.readAllLines(cached);

        String header = "// " + Sig.dotted(namedOuter) + " | " + q.label(c.artifactId()) + " | " + s.def().mappings
                + " | full file: " + cached;
        Out out = new Out(budget);
        out.force(header);
        if (lines != null && !lines.isBlank()) {
            String[] p = lines.split("-");
            int from = Math.max(1, Integer.parseInt(p[0].trim()));
            int to = p.length > 1 ? Math.min(text.size(), Integer.parseInt(p[1].trim())) : text.size();
            numbered(text, from, to, out);
            return out.finish("request a smaller lines= range");
        }
        if (t.member() == null) {
            numbered(text, 1, text.size(), out);
            return out.finish("ask for a member (Class.method) or lines=a-b");
        }
        // Class.a,b,c returns several members in one answer (one request instead of three).
        String[] wanted = t.member().split(",");
        String desc = wanted.length == 1 ? t.desc() : null;
        for (String member : wanted) {
            if (member.isBlank()) continue;
            if (wanted.length > 1) out.line("// ---- " + member.trim());
            member(s, c, member.trim(), desc, namedOuter, text, out, budget);
        }
        return out.finish("use lines=a-b to page");
    }

    private void member(Scope s, QueryService.ClassRow c, String member, String desc, String namedOuter, List<String> text, Out out, int budget)
            throws SQLException, IOException {
        List<QueryService.Resolved> resolved = q.resolveMember(s, c, member, desc);
        if (!resolved.isEmpty() && resolved.getFirst().inheritedFrom() != null) { // follow it to the declaring class
            QueryService.Resolved r = resolved.getFirst();
            out.line("// " + member + " is inherited; declared in " + Sig.dotted(r.owner().named()));
            String inherited = source(s.restrict(null, null), r.owner().name() + "#" + r.member().name() + (desc != null ? desc : ""), null, budget);
            for (String l : inherited.split("\n")) out.line(l);
            return;
        }
        String name = resolved.isEmpty() ? member : resolved.getFirst().member().named();
        boolean isField = !resolved.isEmpty() && resolved.getFirst().member().kind().equals("f");
        if (name.equals("<init>")) name = Sig.simple(namedOuter).replaceAll(".*\\$", "");
        int paramFilter = desc != null && !isField ? Sig.paramCount(desc) : -1;
        List<int[]> ranges = isField ? findField(text, name) : findMethod(text, name, paramFilter);
        if (ranges.isEmpty()) {
            out.line("// no declaration of '" + name + "' in the decompiled class (synthetic or inherited?); try outline, or lines=a-b");
            return;
        }
        for (int[] r : ranges) {
            numbered(text, r[0], r[1], out);
            out.line("");
        }
    }

    private static void numbered(List<String> text, int from, int to, Out out) {
        for (int i = from; i <= to && i <= text.size(); i++) {
            if (!out.line(i + "| " + text.get(i - 1))) out.dropped(to - i);
            if (out.full()) break;
        }
    }

    private static String outer(String internal) {
        int d = internal.indexOf('$');
        return d < 0 ? internal : internal.substring(0, d);
    }

    // ---------------------------------------------------------------- extraction

    private static final Pattern STRINGS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    /** Returns 1-based inclusive line ranges of declarations of {@code name}, including annotations/javadoc above. */
    static List<int[]> findMethod(List<String> text, String name, int paramCount) {
        List<int[]> out = new ArrayList<>();
        Pattern decl = Pattern.compile("^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                + "(?:<[^>]+>\\s+)?(?:[\\w.$<>\\[\\],? ]+\\s+)?" + Pattern.quote(name) + "\\s*\\(");
        for (int i = 0; i < text.size(); i++) {
            String line = text.get(i);
            String trimmed = line.trim();
            if (!decl.matcher(line).find() || trimmed.startsWith("return ") || trimmed.startsWith("new ") || trimmed.contains(" = ")
                    || trimmed.startsWith("this.") || trimmed.startsWith("super.")) continue;
            if (trimmed.endsWith(";") && !trimmed.contains("abstract") && !trimmed.contains("native") && !hasTypePrefix(trimmed, name)) continue;
            if (paramCount >= 0 && countParams(text, i) != paramCount) continue;
            int start = i;
            while (start > 0) {
                String prev = text.get(start - 1).trim();
                if (prev.startsWith("@") || prev.startsWith("*") || prev.startsWith("/**") || prev.startsWith("//")) start--;
                else break;
            }
            int end = blockEnd(text, i);
            out.add(new int[]{start + 1, end + 1});
            i = end;
        }
        return out;
    }

    /** {@code void tick();} (interface/abstract declaration) vs {@code tick();} (a call statement). */
    private static boolean hasTypePrefix(String trimmed, String name) {
        int at = trimmed.indexOf(name + "(");
        if (at < 0) at = trimmed.indexOf(name + " (");
        String prefix = at <= 0 ? "" : trimmed.substring(0, at).trim();
        return trimmed.endsWith(");") && !prefix.isEmpty() && !prefix.endsWith(".") && !prefix.startsWith("return");
    }

    static List<int[]> findField(List<String> text, String name) {
        List<int[]> out = new ArrayList<>();
        Pattern decl = Pattern.compile("^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*[\\w.$<>\\[\\],? ]+\\s+"
                + Pattern.quote(name) + "\\s*(=|;)");
        for (int i = 0; i < text.size(); i++) {
            if (decl.matcher(text.get(i)).find()) out.add(new int[]{i + 1, text.get(i).trim().endsWith(";") ? i + 1 : blockEnd(text, i) + 1});
        }
        return out;
    }

    private static int countParams(List<String> text, int declLine) {
        StringBuilder sig = new StringBuilder();
        for (int i = declLine; i < Math.min(text.size(), declLine + 8); i++) {
            sig.append(text.get(i));
            if (text.get(i).contains(")")) break;
        }
        String s = sig.toString();
        int open = s.indexOf('(');
        int close = s.indexOf(')', open);
        if (open < 0 || close < 0) return -1;
        String params = s.substring(open + 1, close).replaceAll("<[^<>]*>", "").trim();
        return params.isEmpty() ? 0 : params.split(",").length;
    }

    /** Index of the line closing the block that opens at or after {@code from}; {@code from} if no block. */
    private static int blockEnd(List<String> text, int from) {
        int depth = 0;
        boolean opened = false;
        for (int i = from; i < text.size(); i++) {
            String line = STRINGS.matcher(text.get(i)).replaceAll("\"\"");
            int comment = line.indexOf("//");
            if (comment >= 0) line = line.substring(0, comment);
            for (char ch : line.toCharArray()) {
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') depth--;
            }
            if (opened && depth <= 0) return i;
            if (!opened && line.trim().endsWith(";")) return i;
        }
        return text.size() - 1;
    }

    // ---------------------------------------------------------------- decompile / remap

    private static synchronized void decompile(Path jar, String className, List<Path> libraries, Path target) throws IOException {
        Map<String, String> results = new HashMap<>();
        IResultSaver saver = new IResultSaver() {
            @Override public void saveFolder(String path) {}
            @Override public void copyFile(String source, String path, String entryName) {}
            @Override public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
                results.put(qualifiedName, content);
            }
            @Override public void createArchive(String path, String archiveName, Manifest manifest) {}
            @Override public void saveDirEntry(String path, String archiveName, String entryName) {}
            @Override public void copyEntry(String source, String path, String archiveName, String entry) {}
            @Override public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
                results.put(qualifiedName, content);
            }
            @Override public void closeArchive(String path, String archiveName) {}
        };
        Map<String, Object> options = new HashMap<>();
        options.put("dgs", "1");   // generic signatures
        options.put("rsy", "1");   // hide synthetic members
        options.put("rbr", "1");   // hide bridge methods
        options.put("ind", "    ");
        options.put("log", "ERROR");
        options.put("thr", "1");
        IFernflowerLogger quiet = new IFernflowerLogger() {
            @Override public void writeMessage(String message, Severity severity) {}
            @Override public void writeMessage(String message, Severity severity, Throwable t) {}
        };
        Fernflower ff = new Fernflower(saver, options, quiet);
        try {
            ff.addSource(jar.toFile());
            for (Path lib : libraries) ff.addLibrary(lib.toFile());
            ff.addWhitelist(className);
            ff.decompileContext();
        } finally {
            ff.clearContext();
        }
        String content = results.get(className);
        if (content == null) throw new IOException("Decompiler produced no output for " + className + " from " + jar.getFileName());
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Remaps a mod jar from intermediary to Yarn once, with Minecraft on the classpath so overrides get renamed too. */
    private synchronized Path remapped(String sha, FabricBase base) throws IOException {
        Path out = home.resolve("remapped").resolve(sha.substring(0, 16) + "-" + base.id() + ".jar");
        if (Files.exists(out)) return out;
        Path in = home.resolve("artifacts").resolve(sha.substring(0, 2)).resolve(sha + ".jar");
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(base.mappingsFile(), "intermediary", "named"))
                .ignoreConflicts(true)
                .renameInvalidLocals(true)
                .extension(new MixinExtension())
                .build();
        // tiny-remapper's mixin extension prints "[WARN] Cannot remap ..." to stdout for every
        // non-remapped mixin target; harmless here, but it would corrupt CLI output.
        java.io.PrintStream realOut = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(tmp).assumeArchive(true).build()) {
            remapper.readClassPath(base.intermediaryJar());
            remapper.readInputs(in);
            remapper.apply(consumer);
        } finally {
            remapper.finish();
            System.setOut(realOut);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return out;
    }
}
