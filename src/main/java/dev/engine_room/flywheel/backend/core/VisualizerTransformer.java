package dev.engine_room.flywheel.backend.core;

import org.objectweb.asm.*;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Traversal;
import org.spongepowered.asm.transformers.MixinClassWriter;

public final class VisualizerTransformer {

    private static final String FLW_METHOD_NAME = "flw$visualizer";

    private static final String TE_INTERNAL = "net/minecraft/tileentity/TileEntity";
    private static final String ENTITY_INTERNAL = "net/minecraft/entity/Entity";
    private static final String OBJECT_INTERNAL = "java/lang/Object";

    private static final String BE_VISUALIZER_DESC = "()Ldev/engine_room/flywheel/api/visualization/BlockEntityVisualizer;";
    private static final String ENTITY_VISUALIZER_DESC = "()Ldev/engine_room/flywheel/api/visualization/EntityVisualizer;";

    private static final String VISUALIZER_REGISTRY_INTERNAL = "dev/engine_room/flywheel/api/visualization/VisualizerRegistry";
    private static final String BSM_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/CallSite;";

    private static final Handle BE_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainBeCallSite", BSM_DESCRIPTOR, false);

    private static final Handle ENTITY_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainEntityCallSite", BSM_DESCRIPTOR, false);

    private static Kind classify(String parentInternal) {
        if (TE_INTERNAL.equals(parentInternal)) return Kind.BE;
        if (ENTITY_INTERNAL.equals(parentInternal)) return Kind.ENTITY;
        if (OBJECT_INTERNAL.equals(parentInternal)) return Kind.NONE;

        ClassInfo info = ClassInfo.forName(parentInternal);
        if (info == null) {
            return Kind.NONE;
        }
        if (info.hasSuperClass(TE_INTERNAL, Traversal.ALL)) return Kind.BE;
        if (info.hasSuperClass(ENTITY_INTERNAL, Traversal.ALL)) return Kind.ENTITY;
        return Kind.NONE;
    }

    private VisualizerTransformer() {}

    public static byte[] transform(String transformedName, byte[] basicClass) {
        String internal = transformedName.replace('.', '/');
        Kind kind;
        ClassReader cr;
        if (TE_INTERNAL.equals(internal)) {
            kind = Kind.BE;
            cr = new ClassReader(basicClass);
        } else if (ENTITY_INTERNAL.equals(internal)) {
            kind = Kind.ENTITY;
            cr = new ClassReader(basicClass);
        } else {
            cr = new ClassReader(basicClass);
            String superInternal = cr.getSuperName();
            if (superInternal == null) return basicClass;
            kind = classify(superInternal);
            if (kind == Kind.NONE) return basicClass;
        }

        // JVMS §4.4: invokedynamic needs CONSTANT_MethodHandle (tag 15), which is only legal at major ≥ 51.
        boolean pre52 = ((basicClass[6] & 0xFF) << 8 | (basicClass[7] & 0xFF)) < 52;
        ClassWriter cw = new MixinClassWriter(cr, pre52 ? ClassWriter.COMPUTE_FRAMES : 0);
        Injector injector = new Injector(cw, kind, Type.getObjectType(internal));
        cr.accept(injector, pre52 ? ClassReader.SKIP_FRAMES : 0);
        return injector.present ? basicClass : cw.toByteArray();
    }

    private static final class Injector extends ClassVisitor {
        boolean present;

        private final Kind kind;
        private final Object bsmArg;

        Injector(ClassVisitor cv, Kind kind, Object bsmArg) {
            super(Opcodes.ASM9, cv);
            this.kind = kind;
            this.bsmArg = bsmArg;
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName, String[] interfaces) {
            int major = version & 0xFFFF;
            if (major < Opcodes.V1_8) {
                version = Opcodes.V1_8;
            }
            super.visit(version, access, name, sig, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String mName, String mDesc, String sig, String[] excs) {
            if (FLW_METHOD_NAME.equals(mName)) {
                if (!kind.descriptor.equals(mDesc)) {
                    throw new IllegalStateException("Method name collision: " + FLW_METHOD_NAME + mDesc);
                }
                present = true;
            }
            return super.visitMethod(access, mName, mDesc, sig, excs);
        }

        @Override
        public void visitEnd() {
            if (!present) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, FLW_METHOD_NAME, kind.descriptor, null, null);
                mv.visitCode();
                mv.visitInvokeDynamicInsn(FLW_METHOD_NAME, kind.descriptor, kind.bootstrap, bsmArg);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
            }
            super.visitEnd();
        }
    }

    private enum Kind {
        NONE(null, null), BE(BE_VISUALIZER_DESC, BE_BOOTSTRAP), ENTITY(ENTITY_VISUALIZER_DESC, ENTITY_BOOTSTRAP);

        final String descriptor;
        final Handle bootstrap;

        Kind(String descriptor, Handle bootstrap) {
            this.descriptor = descriptor;
            this.bootstrap = bootstrap;
        }
    }
}
