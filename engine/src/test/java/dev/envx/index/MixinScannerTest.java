package dev.envx.index;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds a tiny mod jar in memory with a refmapped @Inject and checks what the parser records. */
class MixinScannerTest {

    @Test
    void injectIsResolvedThroughRefmap() throws Exception {
        byte[] mixinClass = mixinClass();
        String fmj = """
                {"schemaVersion":1,"id":"testmod","version":"1.2.3","mixins":["testmod.mixins.json"]}""";
        String cfg = """
                {"package":"com.example.mixin","refmap":"testmod-refmap.json","mixins":["LivingEntityMixin"],"server":[]}""";
        String refmap = """
                {"mappings":{"com/example/mixin/LivingEntityMixin":{
                   "tick":"Lnet/minecraft/class_1309;method_5773()V",
                   "Lnet/minecraft/entity/Entity;baseTick()V":"Lnet/minecraft/class_1297;method_5670()V"}}}""";
        byte[] jar = jar("fabric.mod.json", fmj.getBytes(StandardCharsets.UTF_8),
                "testmod.mixins.json", cfg.getBytes(StandardCharsets.UTF_8),
                "testmod-refmap.json", refmap.getBytes(StandardCharsets.UTF_8),
                "com/example/mixin/LivingEntityMixin.class", mixinClass);

        ParsedJar pj = new JarParser(dev.envx.mapping.Mappings.identity()).parse("test.jar", jar, "mod");
        assertEquals("testmod", pj.mod.id());
        assertEquals(1, pj.mixins.size(), pj.warnings.toString());
        ParsedJar.MixinRec m = pj.mixins.getFirst();
        assertEquals("net/minecraft/class_1309", m.targetClass());
        assertEquals("Inject", m.kind());
        assertEquals("method_5773", m.targetName());
        assertEquals("()V", m.targetDesc());
        assertEquals("INVOKE", m.atValue());
        assertEquals("Lnet/minecraft/class_1297;method_5670()V", m.atTarget());
        assertTrue(m.cancellable());
        assertEquals("common", m.side());
    }

    private static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/example/mixin/LivingEntityMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType("net/minecraft/class_1309"));
        value.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onTick", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, "tick");
        method.visitEnd();
        AnnotationVisitor at = inject.visitArray("at");
        AnnotationVisitor at0 = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at0.visit("value", "INVOKE");
        at0.visit("target", "Lnet/minecraft/entity/Entity;baseTick()V");
        at0.visitEnd();
        at.visitEnd();
        inject.visit("cancellable", true);
        inject.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] jar(Object... entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < entries.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) entries[i]));
                z.write((byte[]) entries[i + 1]);
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
