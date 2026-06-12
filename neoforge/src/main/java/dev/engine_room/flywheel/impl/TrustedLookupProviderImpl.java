package dev.engine_room.flywheel.impl;

import java.lang.invoke.MethodHandles;

import org.objectweb.asm.*;

import dev.engine_room.flywheel.lib.internal.TrustedLookupProvider;

public final class TrustedLookupProviderImpl implements TrustedLookupProvider {
    private static final MethodHandles.Lookup IMPL_LOOKUP = bootstrapImplLookup();

    @Override
    public MethodHandles.Lookup implLookup() {
        return IMPL_LOOKUP;
    }

    private static MethodHandles.Lookup bootstrapImplLookup() {
        try {
            Class<?> bridge = new LookupBridgeLoader(TrustedLookupProviderImpl.class.getClassLoader()).defineLookupBridge();
            return (MethodHandles.Lookup) bridge.getMethod("lookup").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to bootstrap IMPL_LOOKUP", e);
        }
    }

    private static byte[] lookupBridgeBytes() {
        String className = LookupBridgeLoader.BRIDGE_NAME.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getType("Ljava/lang/invoke/MethodHandles$Lookup;"));
        mv.visitLdcInsn("IMPL_LOOKUP");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/AccessibleObject", "setAccessible", "(Z)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/invoke/MethodHandles$Lookup");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class LookupBridgeLoader extends ClassLoader {
        private static final String BRIDGE_NAME = "dev.engine_room.flywheel.bootstrap.LookupBridge";

        private LookupBridgeLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> defineLookupBridge() {
            byte[] bytes = lookupBridgeBytes();
            return defineClass(BRIDGE_NAME, bytes, 0, bytes.length);
        }
    }
}
