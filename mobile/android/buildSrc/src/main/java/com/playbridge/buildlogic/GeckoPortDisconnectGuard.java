package com.playbridge.buildlogic;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * M157 Port.disconnect() checks disconnected, but disconnectFromExtension() and
 * disconnected() do not. Queued extension callbacks can therefore shut down an
 * already disposed native EventDispatcher. Guard both entry points, marking the
 * port closed BEFORE native cleanup. No exceptions or first-time failures are swallowed.
 */
public final class GeckoPortDisconnectGuard extends ClassVisitor {
    public static final String PORT = "org/mozilla/geckoview/WebExtension$Port";
    private boolean hasFlag;
    private int guardedMethods;

    public GeckoPortDisconnectGuard(ClassVisitor next) { super(Opcodes.ASM9, next); }

    /** Validate the dependency before instrumentation, which skips absent target classes. */
    public static void verifyAar(File file) throws IOException {
        try (ZipFile aar = new ZipFile(file)) {
            var classes = aar.getEntry("classes.jar");
            if (classes == null) {
                throw new IllegalStateException("GeckoView classes.jar missing: review disconnect guard");
            }
            try (ZipInputStream jar = new ZipInputStream(aar.getInputStream(classes))) {
                for (java.util.zip.ZipEntry entry; (entry = jar.getNextEntry()) != null;) {
                    if (entry.getName().equals(PORT + ".class")) {
                        new ClassReader(jar.readAllBytes()).accept(
                            new GeckoPortDisconnectGuard(new ClassWriter(0)), 0);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("GeckoView WebExtension$Port missing: review disconnect guard");
    }

    @Override public FieldVisitor visitField(int access, String name, String descriptor,
            String signature, Object value) {
        if (name.equals("disconnected") && descriptor.equals("Z")) hasFlag = true;
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
            String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        boolean cleanup = name.equals("disconnected") && descriptor.equals("()V");
        boolean callback = name.equals("disconnectFromExtension") &&
            descriptor.equals("(Lorg/mozilla/gecko/util/EventCallback;)V");
        if (!cleanup && !callback) return next;
        guardedMethods++;
        return new MethodVisitor(Opcodes.ASM9, next) {
            private int originalReads, originalWrites, shutdownCalls;
            @Override public void visitCode() {
                super.visitCode();
                Label open = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, PORT, "disconnected", "Z");
                super.visitJumpInsn(Opcodes.IFEQ, open);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(open);
                if (cleanup) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitFieldInsn(Opcodes.PUTFIELD, PORT, "disconnected", "Z");
                }
            }
            @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                if (owner.equals(PORT) && field.equals("disconnected")) {
                    if (opcode == Opcodes.GETFIELD) originalReads++;
                    if (opcode == Opcodes.PUTFIELD) originalWrites++;
                }
                super.visitFieldInsn(opcode, owner, field, desc);
            }
            @Override public void visitMethodInsn(int opcode, String owner, String method,
                    String desc, boolean isInterface) {
                if (owner.equals("org/mozilla/gecko/EventDispatcher") && method.equals("shutdown")) shutdownCalls++;
                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
            }
            @Override public void visitEnd() {
                if (originalReads != 0 || originalWrites != (cleanup ? 1 : 0) ||
                        shutdownCalls != (cleanup ? 1 : 0)) {
                    throw new IllegalStateException("GeckoView port teardown changed: review/remove the disconnect guard");
                }
                super.visitEnd();
            }
        };
    }

    @Override public void visitEnd() {
        if (!hasFlag || guardedMethods != 2) {
            throw new IllegalStateException("GeckoView port teardown shape changed: review disconnect guard");
        }
        super.visitEnd();
    }
}
