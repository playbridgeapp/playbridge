package com.playbridge.buildlogic;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Runs the pinned, real GeckoView Port bytecode with only its native dispatcher replaced. */
public class GeckoPortDisconnectGuardTest {
    private static Map<String, byte[]> classes;

    @BeforeClass public static void loadPinnedGecko() throws Exception {
        classes = new HashMap<>();
        try (ZipFile aar = new ZipFile(new File(System.getProperty("gecko.fixture.aar")))) {
            byte[] jar = aar.getInputStream(aar.getEntry("classes.jar")).readAllBytes();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
                for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null;) {
                    if (e.getName().endsWith(".class")) {
                        classes.put(e.getName().replace('/', '.').replace(".class", ""), zip.readAllBytes());
                    }
                }
            }
        }
    }

    private static byte[] guardedPort() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        new ClassReader(classes.get("org.mozilla.geckoview.WebExtension$Port"))
            .accept(new GeckoPortDisconnectGuard(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    @Test public void upstreamReproducesLateDisconnectNullHandle() throws Exception {
        Harness h = new Harness(false);
        h.cleanup(); // App-side shutdown, then an already queued extension callback.
        try {
            h.extensionDisconnect();
            fail("Unpatched GeckoView should attempt native shutdown twice");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof NullPointerException);
            assertTrue(error.getCause().getMessage().contains("NullHandle"));
        }
        assertEquals(2, h.shutdowns());
    }

    @Test public void lateExtensionCallbackDoesNotRepeatCleanupOrDelegate() throws Exception {
        Harness h = new Harness(true);
        h.cleanup();
        h.extensionDisconnect();
        h.cleanup();
        assertEquals(1, h.shutdowns());
        assertEquals(0, h.callbacks);
    }

    @Test public void duplicateExtensionCallbacksNotifyAndShutdownOnce() throws Exception {
        Harness h = new Harness(true);
        h.extensionDisconnect();
        h.extensionDisconnect();
        assertEquals(1, h.shutdowns());
        assertEquals(1, h.callbacks);
    }

    @Test public void delegateMayReenterCleanupWithoutDoubleShutdown() throws Exception {
        Harness h = new Harness(true);
        h.reenter = true;
        h.extensionDisconnect();
        assertEquals(1, h.shutdowns());
        assertEquals(1, h.callbacks);
    }

    @Test public void firstNativeFailureIsNotSwallowed() throws Exception {
        Harness h = new Harness(true);
        h.dispatcher.getClass().getField("failShutdown").setBoolean(h.dispatcher, true);
        try {
            h.cleanup();
            fail("First-time native failure must still propagate");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof NullPointerException);
        }
    }

    @Test public void alreadyChangedUpstreamImplementationFailsClosed() {
        ClassWriter writer = new ClassWriter(0);
        try {
            new ClassReader(guardedPort()).accept(new GeckoPortDisconnectGuard(writer), 0);
            fail("Dependency changes must require a guard review");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("review"));
        }
    }

    private static final class Harness {
        final Object port, dispatcher;
        final Method cleanup, callback;
        int callbacks;
        boolean reenter;

        Harness(boolean guard) throws Exception {
            Map<String, byte[]> fixture = new HashMap<>(classes);
            fixture.put("org.mozilla.gecko.EventDispatcher", fakeNativeDispatcher());
            if (guard) fixture.put("org.mozilla.geckoview.WebExtension$Port", guardedPort());
            ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = fixture.get(name);
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            Class<?> portClass = loader.loadClass("org.mozilla.geckoview.WebExtension$Port");
            var constructor = portClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            port = constructor.newInstance();
            dispatcher = loader.loadClass("org.mozilla.gecko.EventDispatcher").getConstructor().newInstance();
            Field field = portClass.getDeclaredField("mEventDispatcher");
            field.setAccessible(true);
            field.set(port, dispatcher);
            cleanup = portClass.getDeclaredMethod("disconnected");
            cleanup.setAccessible(true);
            callback = portClass.getDeclaredMethod("disconnectFromExtension",
                loader.loadClass("org.mozilla.gecko.util.EventCallback"));
            callback.setAccessible(true);
            Class<?> delegateClass = loader.loadClass("org.mozilla.geckoview.WebExtension$PortDelegate");
            Object delegate = Proxy.newProxyInstance(loader, new Class<?>[]{delegateClass}, (proxy, method, args) -> {
                if (method.getName().equals("onDisconnect")) {
                    callbacks++;
                    if (reenter) cleanup();
                }
                return null;
            });
            Field delegateField = portClass.getDeclaredField("delegate");
            delegateField.setAccessible(true);
            delegateField.set(port, delegate);
        }
        void cleanup() throws Exception { cleanup.invoke(port); }
        void extensionDisconnect() throws Exception { callback.invoke(port, new Object[]{null}); }
        int shutdowns() throws Exception { return dispatcher.getClass().getField("shutdowns").getInt(dispatcher); }
    }

    /** Models a disposed JNI peer, not GeckoView's Java port logic (which is real bytecode). */
    private static byte[] fakeNativeDispatcher() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = "org/mozilla/gecko/EventDispatcher";
        w.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC, "shutdowns", "I", null, null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC, "failShutdown", "Z", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode(); m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC, "shutdown", "()V", null, null);
        m.visitCode(); m.visitVarInsn(Opcodes.ALOAD, 0); m.visitInsn(Opcodes.DUP);
        m.visitFieldInsn(Opcodes.GETFIELD, name, "shutdowns", "I");
        m.visitInsn(Opcodes.ICONST_1); m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTFIELD, name, "shutdowns", "I");
        Label fail = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 0); m.visitFieldInsn(Opcodes.GETFIELD, name, "shutdowns", "I");
        m.visitInsn(Opcodes.ICONST_1); m.visitJumpInsn(Opcodes.IF_ICMPGT, fail);
        m.visitVarInsn(Opcodes.ALOAD, 0); m.visitFieldInsn(Opcodes.GETFIELD, name, "failShutdown", "Z");
        m.visitJumpInsn(Opcodes.IFNE, fail); m.visitInsn(Opcodes.RETURN);
        m.visitLabel(fail); m.visitTypeInsn(Opcodes.NEW, "java/lang/NullPointerException"); m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn("NativeException NullHandle() [T = mozilla::widget::EventDispatcher]");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/NullPointerException", "<init>", "(Ljava/lang/String;)V", false);
        m.visitInsn(Opcodes.ATHROW); m.visitMaxs(0, 0); m.visitEnd();
        w.visitEnd(); return w.toByteArray();
    }
}
