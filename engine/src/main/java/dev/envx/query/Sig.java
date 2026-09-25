package dev.envx.query;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/** Human/agent-friendly rendering of JVM names and descriptors. */
public final class Sig {
    private Sig() {}

    public static String simple(String internal) {
        if (internal == null) return "?";
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    public static String dotted(String internal) {
        return internal == null ? "?" : internal.replace('/', '.');
    }

    /** {@code (Lnet/minecraft/entity/Entity;F)V} -> {@code (Entity, float) -> void}. */
    public static String method(String desc) {
        try {
            Type t = Type.getMethodType(desc);
            List<String> args = new ArrayList<>();
            for (Type a : t.getArgumentTypes()) args.add(type(a));
            return "(" + String.join(", ", args) + ") -> " + type(t.getReturnType());
        } catch (RuntimeException e) {
            return desc;
        }
    }

    public static String field(String desc) {
        try {
            return type(Type.getType(desc));
        } catch (RuntimeException e) {
            return desc;
        }
    }

    private static String type(Type t) {
        return switch (t.getSort()) {
            case Type.ARRAY -> type(t.getElementType()) + "[]".repeat(t.getDimensions());
            case Type.OBJECT -> simple(t.getInternalName());
            default -> t.getClassName();
        };
    }

    public static int paramCount(String desc) {
        try {
            return Type.getArgumentTypes(desc).length;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
