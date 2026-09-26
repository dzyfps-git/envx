package dev.sevli.query;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A parsed lookup such as {@code LivingEntity.tick}, {@code net.minecraft.class_1309#method_5773},
 * {@code class_3218.method_18765(Lnet/minecraft/class_1297;)V} or a stack frame
 * {@code at net.minecraft.server.world.ServerWorld.tick(ServerWorld.java:123)}.
 * Either part may be null.
 */
public record Target(String cls, String member, String desc) {
    private static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(method|field|comp)_\\d+");
    private static final Pattern FRAME_SUFFIX = Pattern.compile("\\(([\\w$]+\\.java|Unknown Source|Native Method)(:\\d+)?\\)\\s*(~?\\[.*)?$");

    private static final Pattern LOADER_PREFIX = Pattern.compile("^[\\w.-]*//");
    private static final Pattern MODULE_PREFIX = Pattern.compile("^[\\w.]+(@[\\w.+-]+)?/(?=([\\w$]+\\.)+[\\w$<>]+$)");

    public static Target parse(String raw) {
        String q = raw.trim();
        if (q.startsWith("at ")) q = q.substring(3).trim();
        q = FRAME_SUFFIX.matcher(q).replaceAll("");
        q = LOADER_PREFIX.matcher(q).replaceFirst(""); // "knot//", "app//" as Fabric and the JDK print them
        q = MODULE_PREFIX.matcher(q).replaceFirst(""); // "java.base/java.lang.Thread.run"
        String desc = null;
        int paren = q.indexOf('(');
        if (paren >= 0) {
            String d = q.substring(paren);
            if (d.contains(")") && (d.equals("()") || d.matches("\\(.*\\).+"))) desc = d.equals("()") ? null : d;
            q = q.substring(0, paren);
        }
        int colon = q.indexOf(':');
        if (colon > 0 && !q.contains("::")) { // field descriptor form name:Ldesc;
            desc = q.substring(colon + 1);
            q = q.substring(0, colon);
        }
        String cls;
        String member;
        if (q.contains("#")) {
            cls = q.substring(0, q.indexOf('#'));
            member = q.substring(q.indexOf('#') + 1);
        } else if (q.contains("::")) {
            cls = q.substring(0, q.indexOf("::"));
            member = q.substring(q.indexOf("::") + 2);
        } else {
            int dot = Math.max(q.lastIndexOf('.'), q.lastIndexOf('/'));
            String last = dot >= 0 ? q.substring(dot + 1) : q;
            if (looksLikeMember(last)) {
                cls = dot >= 0 ? q.substring(0, dot) : null;
                member = last;
            } else {
                cls = q;
                member = null;
            }
        }
        if (cls != null && cls.isBlank()) cls = null;
        if (member != null && member.isBlank()) member = null;
        return new Target(cls, member, desc);
    }

    private static boolean looksLikeMember(String s) {
        if (s.isEmpty()) return false;
        if (s.equals("<init>") || s.equals("<clinit>")) return true;
        if (INTERMEDIARY_MEMBER.matcher(s).matches()) return true;
        if (s.startsWith("class_")) return false;
        char c = s.charAt(0);
        return Character.isLowerCase(c) || c == '*' || (Character.isUpperCase(c) && s.equals(s.toUpperCase(Locale.ROOT)) && s.length() > 1 && s.contains("_"));
    }
}
