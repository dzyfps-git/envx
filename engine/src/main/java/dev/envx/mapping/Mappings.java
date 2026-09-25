package dev.envx.mapping;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates runtime (intermediary) names to the display flavour (Yarn).
 *
 * <p>Intermediary member names ({@code method_1234}, {@code field_1234}, {@code comp_1234}) are
 * globally unique per member family, so they can be translated without knowing the owner class.
 * That is what makes mod jars translatable without a classpath: a mod overriding
 * {@code LivingEntity.tick} carries {@code method_5773} in its own class.
 */
public final class Mappings {
    private static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;]+);");

    private final String id;
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> members = new HashMap<>();

    private Mappings(String id) {
        this.id = id;
    }

    /** No renaming at all; for jars that need no translation and for tests. */
    public static Mappings identity() {
        return new Mappings("identity");
    }

    /** Loads a tiny v2 file that has {@code intermediary} and {@code named} namespaces. */
    public static Mappings load(String id, Path tinyFile) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(tinyFile, tree);
        int inter = tree.getNamespaceId("intermediary");
        int named = tree.getNamespaceId("named");
        if (inter == MappingTree.NULL_NAMESPACE_ID || named == MappingTree.NULL_NAMESPACE_ID) {
            throw new IOException("Mapping file lacks intermediary/named namespaces: " + tinyFile);
        }
        Mappings m = new Mappings(id);
        for (MappingTree.ClassMapping c : tree.getClasses()) {
            String i = c.getName(inter);
            String n = c.getName(named);
            if (i != null && n != null) m.classes.put(i, n);
            for (MappingTree.MethodMapping mm : c.getMethods()) put(m.members, mm.getName(inter), mm.getName(named));
            for (MappingTree.FieldMapping fm : c.getFields()) put(m.members, fm.getName(inter), fm.getName(named));
        }
        return m;
    }

    private static void put(Map<String, String> map, String from, String to) {
        if (from != null && to != null && !from.equals(to)) map.put(from, to);
    }

    public String id() {
        return id;
    }

    /** Internal class name -> display name; unknown names (mod classes) pass through. */
    public String cls(String internal) {
        if (internal == null) return null;
        String direct = classes.get(internal);
        if (direct != null) return direct;
        int dollar = internal.lastIndexOf('$');
        if (dollar > 0) { // anonymous/local inner classes are not in the mappings, their outer class is
            String outer = cls(internal.substring(0, dollar));
            if (!outer.equals(internal.substring(0, dollar))) return outer + internal.substring(dollar);
        }
        return internal;
    }

    public String member(String name) {
        return members.getOrDefault(name, name);
    }

    public String desc(String descriptor) {
        if (descriptor == null || descriptor.indexOf('L') < 0) return descriptor;
        Matcher m = CLASS_IN_DESC.matcher(descriptor);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("L" + cls(m.group(1)) + ";"));
        m.appendTail(sb);
        return sb.toString();
    }

    public int classCount() {
        return classes.size();
    }
}
