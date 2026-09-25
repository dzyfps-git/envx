package dev.envx.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.envx.index.ParsedJar.ClassRec;
import dev.envx.index.ParsedJar.MemberRec;
import dev.envx.mapping.Mappings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a jar into a {@link ParsedJar} by reading bytecode directly (no decompilation).
 * Stateless apart from the mappings, so many jars can be parsed in parallel.
 */
public final class JarParser {
    /** Text resources worth keeping for {@code grep}; everything else is only listed. */
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;

    private final Mappings mappings;

    public JarParser(Mappings mappings) {
        this.mappings = mappings;
    }

    public ParsedJar parse(String fileName, byte[] jarBytes, String kind) throws IOException {
        ParsedJar out = new ParsedJar();
        out.fileName = fileName;
        out.size = jarBytes.length;
        out.sha256 = sha256(jarBytes);
        out.kind = kind;
        out.bytes = jarBytes;

        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> texts = new LinkedHashMap<>();
        Map<String, Long> otherResources = new LinkedHashMap<>();
        Map<String, byte[]> nestedJars = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.endsWith(".class")) {
                    if (!name.startsWith("META-INF/versions/")) classes.put(name.substring(0, name.length() - 6), readAll(zin));
                } else if (name.endsWith(".jar")) {
                    nestedJars.put(name, readAll(zin));
                } else if (isText(name)) {
                    byte[] data = readAll(zin);
                    texts.put(name, data);
                } else if (!name.startsWith("assets/") || name.contains("/lang/")) {
                    otherResources.put(name, Math.max(e.getSize(), 0));
                }
            }
        }

        JsonObject modJson = parseJson(texts.get("fabric.mod.json"), out, "fabric.mod.json");
        if (modJson != null) out.mod = modMeta(modJson);

        Map<String, MixinScanner.MixinTarget> mixinClasses = modJson == null ? Map.of()
                : new MixinScanner().collectConfigs(modJson, texts, out);

        for (var ce : classes.entrySet()) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(ce.getValue()).accept(node, ClassReader.SKIP_FRAMES);
                MixinScanner.MixinTarget mt = mixinClasses.get(node.name);
                out.classes.add(toRec(node, mt != null));
                if (mt != null) new MixinScanner().scanClass(node, mt, out);
            } catch (RuntimeException ex) {
                out.warnings.add("unreadable class " + ce.getKey() + ": " + ex);
            }
        }

        for (var te : texts.entrySet()) {
            byte[] d = te.getValue();
            boolean keep = d.length <= MAX_RESOURCE_BYTES && keepText(te.getKey());
            out.resources.add(new ParsedJar.ResourceRec(te.getKey(), d.length, keep ? d : null));
        }
        otherResources.forEach((p, s) -> out.resources.add(new ParsedJar.ResourceRec(p, s, null)));

        for (var ne : nestedJars.entrySet()) {
            String simple = ne.getKey().substring(ne.getKey().lastIndexOf('/') + 1);
            try {
                out.nested.add(parse(simple, ne.getValue(), "library"));
            } catch (IOException ex) {
                out.warnings.add("unreadable nested jar " + ne.getKey() + ": " + ex.getMessage());
            }
        }
        for (ParsedJar n : out.nested) if (n.mod != null) n.kind = "mod";
        return out;
    }

    private ClassRec toRec(ClassNode node, boolean isMixin) {
        List<MemberRec> members = new ArrayList<>(node.methods.size() + node.fields.size());
        Set<String> refs = new HashSet<>();
        for (FieldNode f : node.fields) {
            members.add(new MemberRec('f', f.name, f.desc, mappings.member(f.name), mappings.desc(f.desc), f.access, null));
        }
        for (MethodNode m : node.methods) {
            Integer line = null;
            if (m.instructions != null) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (line == null && insn instanceof LineNumberNode ln) line = ln.line;
                    collectRef(insn, refs);
                }
            }
            members.add(new MemberRec('m', m.name, m.desc, mappings.member(m.name), mappings.desc(m.desc), m.access, line));
        }
        refs.remove(node.name);
        String interfaces = node.interfaces == null || node.interfaces.isEmpty() ? null : String.join(",", node.interfaces);
        return new ClassRec(node.name, mappings.cls(node.name), node.superName, interfaces, node.access, isMixin, members, refs);
    }

    private static void collectRef(AbstractInsnNode insn, Set<String> refs) {
        switch (insn) {
            case MethodInsnNode mi -> addOwner(mi.owner, refs);
            case FieldInsnNode fi -> addOwner(fi.owner, refs);
            case TypeInsnNode ti -> addOwner(ti.desc, refs);
            case LdcInsnNode ldc when ldc.cst instanceof Type t && t.getSort() == Type.OBJECT -> addOwner(t.getInternalName(), refs);
            case InvokeDynamicInsnNode indy -> {
                for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) addOwner(h.getOwner(), refs);
            }
            default -> { }
        }
    }

    private static void addOwner(String owner, Set<String> refs) {
        if (owner == null || owner.startsWith("[")) return;
        // JDK types are never the answer to "who uses X" questions and would dominate the table.
        if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/")) return;
        refs.add(owner);
    }

    private static ParsedJar.ModMeta modMeta(JsonObject j) {
        JsonObject compact = new JsonObject();
        for (String k : List.of("environment", "entrypoints", "mixins", "accessWidener", "depends", "recommends",
                "breaks", "conflicts", "provides", "jars")) {
            if (j.has(k)) compact.add(k, j.get(k));
        }
        return new ParsedJar.ModMeta(str(j, "id"), str(j, "version"), str(j, "name"), compact.toString());
    }

    static JsonObject parseJson(byte[] data, ParsedJar out, String what) {
        if (data == null) return null;
        try {
            JsonElement el = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            out.warnings.add("invalid json " + what + ": " + ex.getMessage());
            return null;
        }
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) if (x.isJsonPrimitive()) out.add(x.getAsString());
        } else if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static boolean isText(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".json") || n.endsWith(".mcfunction") || n.endsWith(".toml") || n.endsWith(".properties")
                || n.endsWith(".accesswidener") || n.endsWith(".cfg") || n.endsWith(".txt") || n.endsWith(".mcmeta")
                || n.endsWith(".js") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".unpick");
    }

    /** Keeps data-driven content and mod metadata; skips client assets (models, blockstates, ...). */
    private static boolean keepText(String name) {
        if (name.startsWith("assets/")) return name.contains("/lang/en_us");
        return true;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
