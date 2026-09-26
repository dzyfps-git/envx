package dev.sevli.index;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads mixin configs, refmaps and mixin class annotations into {@link ParsedJar.MixinRec}s.
 *
 * <p>Everything here is what a mod <em>declares</em>. Whether a mixin is actually applied also
 * depends on config plugins, MixinSquared cancellations and load order, which only a runtime probe
 * can observe. Target strings are resolved through the refmap when one exists; jars built with
 * Loom's non-legacy mixin processing carry intermediary names directly and need no refmap.
 */
final class MixinScanner {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    /** Per mixin class: which config declared it, on which side, and its refmap entries. */
    record MixinTarget(String config, String side, int configPriority, String plugin, Map<String, String> refmap) {}

    Map<String, MixinTarget> collectConfigs(JsonObject modJson, Map<String, byte[]> texts, ParsedJar out) {
        Map<String, MixinTarget> result = new HashMap<>();
        JsonElement mixins = modJson.get("mixins");
        if (mixins == null || !mixins.isJsonArray()) return result;
        for (JsonElement entry : mixins.getAsJsonArray()) {
            String configName;
            String entrySide = null;
            if (entry.isJsonPrimitive()) {
                configName = entry.getAsString();
            } else if (entry.isJsonObject()) {
                configName = JarParser.str(entry.getAsJsonObject(), "config");
                entrySide = JarParser.str(entry.getAsJsonObject(), "environment");
            } else continue;
            if (configName == null) continue;
            JsonObject cfg = JarParser.parseJson(texts.get(configName), out, configName);
            if (cfg == null) {
                out.warnings.add("mixin config not found: " + configName);
                continue;
            }
            String pkg = JarParser.str(cfg, "package");
            String plugin = JarParser.str(cfg, "plugin");
            int priority = cfg.has("priority") ? cfg.get("priority").getAsInt() : 1000;
            Map<String, Map<String, String>> refmap = readRefmap(texts, JarParser.str(cfg, "refmap"), out);
            for (String side : List.of("mixins", "server", "client")) {
                String effectiveSide = side.equals("mixins") ? (entrySide == null || entrySide.equals("*") ? "common" : entrySide) : side;
                for (String simple : JarParser.strings(cfg.get(side))) {
                    String internal = ((pkg == null || pkg.isEmpty() ? "" : pkg + ".") + simple).replace('.', '/');
                    result.put(internal, new MixinTarget(configName, effectiveSide, priority, plugin,
                            refmap.getOrDefault(internal, Map.of())));
                }
            }
        }
        return result;
    }

    private static Map<String, Map<String, String>> readRefmap(Map<String, byte[]> texts, String name, ParsedJar out) {
        Map<String, Map<String, String>> result = new HashMap<>();
        if (name == null) return result;
        JsonObject json = JarParser.parseJson(texts.get(name), out, name);
        if (json == null || !json.has("mappings")) return result;
        for (var cls : json.getAsJsonObject("mappings").entrySet()) {
            Map<String, String> m = new HashMap<>();
            for (var e : cls.getValue().getAsJsonObject().entrySet()) m.put(e.getKey(), e.getValue().getAsString());
            result.put(cls.getKey(), m);
        }
        return result;
    }

    void scanClass(ClassNode node, MixinTarget mt, ParsedJar out) {
        AnnotationNode mixinAnn = find(node.invisibleAnnotations, MIXIN);
        if (mixinAnn == null) mixinAnn = find(node.visibleAnnotations, MIXIN);
        if (mixinAnn == null) {
            out.warnings.add("listed mixin without @Mixin: " + node.name);
            return;
        }
        List<String> targets = new ArrayList<>();
        int priority = mt.configPriority();
        Object values = value(mixinAnn, "value");
        if (values instanceof List<?> l) for (Object o : l) if (o instanceof Type t) targets.add(t.getInternalName());
        Object strTargets = value(mixinAnn, "targets");
        if (strTargets instanceof List<?> l) {
            for (Object o : l) targets.add(mt.refmap().getOrDefault((String) o, (String) o).replace('.', '/'));
        }
        if (value(mixinAnn, "priority") instanceof Integer p) priority = p;

        boolean any = false;
        for (MethodNode m : node.methods) {
            for (AnnotationNode ann : annotations(m)) {
                String kind = injectorKind(ann.desc);
                if (kind == null) continue;
                any = true;
                for (String target : targets) {
                    for (String raw : methodSelectors(ann, kind, m)) {
                        String resolved = mt.refmap().getOrDefault(raw, raw);
                        Selector sel = Selector.parse(resolved);
                        AnnotationNode at = firstAt(ann);
                        String atValue = at == null ? null : (String) value(at, "value");
                        String atTarget = at == null ? null : (String) value(at, "target");
                        if (atTarget != null) atTarget = mt.refmap().getOrDefault(atTarget, atTarget);
                        boolean cancellable = Boolean.TRUE.equals(value(ann, "cancellable"));
                        out.mixins.add(new ParsedJar.MixinRec(mt.config(), node.name, target, kind, m.name, raw,
                                sel.name(), sel.desc(), atValue, atTarget, priority, cancellable, mt.side(), mt.plugin()));
                    }
                }
            }
        }
        if (!any) { // mixins that only add interfaces/fields/@Unique methods still touch the target
            for (String target : targets) {
                out.mixins.add(new ParsedJar.MixinRec(mt.config(), node.name, target, "class", null, null, null, null,
                        null, null, priority, false, mt.side(), mt.plugin()));
            }
        }
    }

    /** Returns the annotation's simple name if it is an injector/accessor we record, else null. */
    static String injectorKind(String desc) {
        if (desc.equals(OVERWRITE)) return "Overwrite";
        if (desc.equals(ACCESSOR)) return "Accessor";
        if (desc.equals(INVOKER)) return "Invoker";
        if (desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
            String simple = desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1);
            // @At/@Slice/@Desc/@Local etc. are parameters, not injectors
            return switch (simple) {
                case "At", "Slice", "Desc", "Constant", "Coerce", "Group", "Surrogate", "Local", "Share", "Cancellable" -> null;
                default -> simple;
            };
        }
        return null;
    }

    private List<String> methodSelectors(AnnotationNode ann, String kind, MethodNode handler) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case "Overwrite" -> out.add(handler.name + handler.desc);
            case "Accessor", "Invoker" -> {
                Object v = value(ann, "value");
                out.add(v instanceof String s && !s.isEmpty() ? s : accessorTargetFromName(handler.name));
            }
            default -> {
                Object v = value(ann, "method");
                if (v instanceof List<?> l) for (Object o : l) if (o instanceof String s) out.add(s);
                if (out.isEmpty()) out.add("?");
            }
        }
        return out;
    }

    private static String accessorTargetFromName(String name) {
        for (String prefix : List.of("get", "set", "is", "call", "invoke", "create", "new")) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                String rest = name.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    private static AnnotationNode firstAt(AnnotationNode ann) {
        Object at = value(ann, "at");
        if (at instanceof AnnotationNode a) return a;
        if (at instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof AnnotationNode a) return a;
        return null;
    }

    private static List<AnnotationNode> annotations(MethodNode m) {
        List<AnnotationNode> all = new ArrayList<>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        return all;
    }

    private static AnnotationNode find(List<AnnotationNode> list, String desc) {
        if (list == null) return null;
        for (AnnotationNode a : list) if (a.desc.equals(desc)) return a;
        return null;
    }

    static Object value(AnnotationNode ann, String key) {
        if (ann.values == null) return null;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (key.equals(ann.values.get(i))) return ann.values.get(i + 1);
        }
        return null;
    }

    /** A parsed mixin target selector such as {@code Lnet/minecraft/class_1309;method_5773()V}. */
    record Selector(String owner, String name, String desc) {
        static Selector parse(String s) {
            String owner = null;
            String rest = s;
            if (rest.startsWith("L") && rest.contains(";")) {
                owner = rest.substring(1, rest.indexOf(';'));
                rest = rest.substring(rest.indexOf(';') + 1);
            }
            int paren = rest.indexOf('(');
            int colon = rest.indexOf(':');
            String name = rest;
            String desc = null;
            if (paren >= 0) {
                name = rest.substring(0, paren);
                desc = rest.substring(paren);
            } else if (colon >= 0) {
                name = rest.substring(0, colon);
                desc = rest.substring(colon + 1);
            }
            return new Selector(owner, name, desc);
        }
    }
}
