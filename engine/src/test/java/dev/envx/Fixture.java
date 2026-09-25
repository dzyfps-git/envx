package dev.envx;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builders for a made-up environment: a two-class Minecraft base and small mods (no network, no real index). */
final class Fixture {
    private Fixture() {}


    /**
     * A two-class Minecraft: Entity.tick and LivingEntity.tick, intermediary and Yarn-named, plus mappings.
     * LivingEntity also has a {@code persistent} field read only through {@code isPersistent()}, which
     * {@code checkDespawn()} calls: a filter on "despawn" must still surface the field.
     */
    static void base(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("minecraft-merged-intermediary.jar"), jar(Map.of(
                "net/minecraft/class_1297.class", mcClass("net/minecraft/class_1297", "java/lang/Object", "method_5773"),
                "net/minecraft/class_1309.class", mcClass("net/minecraft/class_1309", "net/minecraft/class_1297", "method_5773",
                        "field_90001", "method_90002", "method_90003"))));
        Files.write(dir.resolve("minecraft-merged-named.jar"), jar(Map.of(
                "net/minecraft/entity/Entity.class", mcClass("net/minecraft/entity/Entity", "java/lang/Object", "tick"),
                "net/minecraft/entity/LivingEntity.class", mcClass("net/minecraft/entity/LivingEntity", "net/minecraft/entity/Entity", "tick",
                        "persistent", "isPersistent", "checkDespawn"))));
        Files.writeString(dir.resolve("mappings.tiny"), String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary\tnamed",
                "c\ta\tnet/minecraft/class_1297\tnet/minecraft/entity/Entity",
                "\tm\t()V\ta\tmethod_5773\ttick",
                "c\tb\tnet/minecraft/class_1309\tnet/minecraft/entity/LivingEntity",
                "\tm\t()V\ta\tmethod_5773\ttick",
                "\tf\tZ\tc\tfield_90001\tpersistent",
                "\tm\t()Z\td\tmethod_90002\tisPersistent",
                "\tm\t()V\te\tmethod_90003\tcheckDespawn", ""));
    }

    static byte[] mcClass(String name, String superName, String tick, String... despawn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (despawn.length == 3) { // a field, its getter, and a method that uses the field only through the getter
            cw.visitField(Opcodes.ACC_PRIVATE, despawn[0], "Z", null, null).visitEnd();
            MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, despawn[1], "()Z", null, null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 0);
            get.visitFieldInsn(Opcodes.GETFIELD, name, despawn[0], "Z");
            get.visitInsn(Opcodes.IRETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
            MethodVisitor check = cw.visitMethod(Opcodes.ACC_PUBLIC, despawn[2], "()V", null, null);
            check.visitCode();
            check.visitVarInsn(Opcodes.ALOAD, 0);
            check.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, despawn[1], "()Z", false);
            check.visitInsn(Opcodes.POP);
            check.visitInsn(Opcodes.RETURN);
            check.visitMaxs(0, 0);
            check.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, tick, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod with one {@code @Inject(method = "method_5773", at = @At("HEAD"))} into LivingEntity. */
    static byte[] mod(String id, String version, String mixinClass, String handler) throws Exception {
        String pkg = mixinClass.substring(0, mixinClass.lastIndexOf('/')).replace('/', '.');
        String simple = mixinClass.substring(mixinClass.lastIndexOf('/') + 1);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"mixins\":[\"" + id + ".mixins.json\"]}").getBytes(StandardCharsets.UTF_8));
        entries.put(id + ".mixins.json", ("{\"package\":\"" + pkg + "\",\"mixins\":[\"" + simple + "\"]}").getBytes(StandardCharsets.UTF_8));
        // Data that spawns mobs without any code named for it (structure spawn overrides).
        entries.put("data/" + id + "/worldgen/structure/camp.json", "{\"spawn_overrides\":{\"monster\":{}}}".getBytes(StandardCharsets.UTF_8));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, mixinClass, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType("net/minecraft/class_1309"));
        value.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, handler, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, "method_5773()V");
        method.visitEnd();
        AnnotationVisitor at = inject.visitArray("at");
        AnnotationVisitor at0 = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at0.visit("value", "HEAD");
        at0.visitEnd();
        at.visitEnd();
        inject.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
        cw.visitEnd();
        entries.put(mixinClass + ".class", cw.toByteArray());
        String ticker = "dev/" + id + "/Ticker";
        ClassWriter tw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        tw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ticker, null, "java/lang/Object", null);
        MethodVisitor tick = tw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tickIt", "(Lnet/minecraft/class_1309;)V", null, null);
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1309", "method_5773", "()V", false);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        tw.visitEnd();
        entries.put(ticker + ".class", tw.toByteArray());
        return jar(entries);
    }

    static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
