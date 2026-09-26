package dev.sevli.fabric;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseDownloadTest {
    private static byte[] cls(String field, String method) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd();
        var m = w.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    @Test
    void aClassOnBothSidesGetsTheServerOnlyMembers() {
        ClassNode c = new ClassNode();
        new ClassReader(BaseDownload.mergeClass(cls("shared", "clientOnly"), cls("shared", "serverOnly"))).accept(c, 0);
        assertEquals(List.of("shared"), c.fields.stream().map(f -> f.name).toList());
        assertEquals(List.of("clientOnly", "serverOnly"), c.methods.stream().map(m -> m.name).toList());
    }

    @Test
    void aDownloadWithTheWrongHashIsRefused() {
        byte[] data = "jar".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> BaseDownload.check(data, "SHA-1", "0000", "x"));
        assertDoesNotThrow(() -> BaseDownload.check(data, "SHA-256",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)), "x"));
    }
}
