package dev.engine_room.flywheel.backend.core;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Traversal;
import org.spongepowered.asm.transformers.MixinClassWriter;

public final class VisualizerTransformer {

    private static final String FLW_METHOD_NAME = "flw$visualizer";
    private static final String SKIP_METHOD_NAME = "flw$skipVanillaRender";
    private static final String SKIP_METHOD_DESC = "()Z";
    private static final String CAN_METHOD_NAME = "flw$canVisualize";
    private static final String CAN_METHOD_DESC = "()Z";
    private static final String CREATE_METHOD_NAME = "flw$createVisual";

    private static final String TE_INTERNAL = "net/minecraft/tileentity/TileEntity";
    private static final String ENTITY_INTERNAL = "net/minecraft/entity/Entity";
    private static final String OBJECT_INTERNAL = "java/lang/Object";
    private static final String VIS_CONTEXT_INTERNAL = "dev/engine_room/flywheel/api/visualization/VisualizationContext";
    private static final String BE_VISUAL_INTERNAL = "dev/engine_room/flywheel/api/visual/BlockEntityVisual";
    private static final String ENTITY_VISUAL_INTERNAL = "dev/engine_room/flywheel/api/visual/EntityVisual";

    private static final String BE_VISUALIZER_DESC = "()Ldev/engine_room/flywheel/api/visualization/BlockEntityVisualizer;";
    private static final String ENTITY_VISUALIZER_DESC = "()Ldev/engine_room/flywheel/api/visualization/EntityVisualizer;";

    private static final String VISUALIZER_REGISTRY_INTERNAL = "dev/engine_room/flywheel/api/visualization/VisualizerRegistry";
    private static final String BSM_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/CallSite;";

    private static final Handle BE_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainBeCallSite", BSM_DESCRIPTOR, false);

    private static final Handle ENTITY_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainEntityCallSite", BSM_DESCRIPTOR, false);

    private static final Handle BE_SKIP_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainBeSkipCallSite", BSM_DESCRIPTOR, false);

    private static final Handle ENTITY_SKIP_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainEntitySkipCallSite", BSM_DESCRIPTOR, false);

    private static final Handle BE_CAN_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainBeCanCallSite", BSM_DESCRIPTOR, false);

    private static final Handle ENTITY_CAN_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainEntityCanCallSite", BSM_DESCRIPTOR, false);

    private static final Handle BE_CREATE_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainBeCreateCallSite", BSM_DESCRIPTOR, false);

    private static final Handle ENTITY_CREATE_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            VISUALIZER_REGISTRY_INTERNAL, "obtainEntityCreateCallSite", BSM_DESCRIPTOR, false);

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
        // JVMS §4.9.1: JSR/RET are illegal at major ≥ 51, so bumped pre-51 bodies must be JSR-inlined,
        boolean pre51 = ((basicClass[6] & 0xFF) << 8 | (basicClass[7] & 0xFF)) < 51;
        ClassWriter cw = new MixinClassWriter(cr, pre51 ? ClassWriter.COMPUTE_FRAMES : 0);
        Injector injector = new Injector(cw, kind, Type.getObjectType(internal), pre51);
        cr.accept(injector, pre51 ? ClassReader.SKIP_FRAMES : 0);
        return injector.allPresent() ? basicClass : cw.toByteArray();
    }

    private static final class Injector extends ClassVisitor {
        boolean presentVisualizer;
        boolean presentSkip;
        boolean presentCan;
        boolean presentCreate;

        private final Kind kind;
        private final Object bsmArg;
        private final boolean inlineJsr;

        Injector(ClassVisitor cv, Kind kind, Object bsmArg, boolean inlineJsr) {
            super(Opcodes.ASM9, cv);
            this.kind = kind;
            this.bsmArg = bsmArg;
            this.inlineJsr = inlineJsr;
        }

        boolean allPresent() {
            return presentVisualizer && presentSkip && presentCan && presentCreate;
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName, String[] interfaces) {
            int major = version & 0xFFFF;
            if (major < Opcodes.V1_7) {
                version = Opcodes.V1_7;
            }
            super.visit(version, access, name, sig, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String mName, String mDesc, String sig, String[] excs) {
            if (FLW_METHOD_NAME.equals(mName)) {
                if (!kind.descriptor.equals(mDesc)) {
                    throw new IllegalStateException("Method name collision: " + FLW_METHOD_NAME + mDesc);
                }
                presentVisualizer = true;
            } else if (SKIP_METHOD_NAME.equals(mName)) {
                if (!SKIP_METHOD_DESC.equals(mDesc)) {
                    throw new IllegalStateException("Method name collision: " + SKIP_METHOD_NAME + mDesc);
                }
                presentSkip = true;
            } else if (CAN_METHOD_NAME.equals(mName)) {
                if (!CAN_METHOD_DESC.equals(mDesc)) {
                    throw new IllegalStateException("Method name collision: " + CAN_METHOD_NAME + mDesc);
                }
                presentCan = true;
            } else if (CREATE_METHOD_NAME.equals(mName)) {
                if (!kind.createMethodDescriptor.equals(mDesc)) {
                    throw new IllegalStateException("Method name collision: " + CREATE_METHOD_NAME + mDesc);
                }
                presentCreate = true;
            }
            MethodVisitor mv = super.visitMethod(access, mName, mDesc, sig, excs);
            if (inlineJsr && mv != null) {
                mv = new JSRInlinerAdapter(mv, access, mName, mDesc, sig, excs);
            }
            return mv;
        }

        @Override
        public void visitEnd() {
            if (!presentVisualizer) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, FLW_METHOD_NAME, kind.descriptor, null, null);
                mv.visitCode();
                mv.visitInvokeDynamicInsn(FLW_METHOD_NAME, kind.descriptor, kind.bootstrap, bsmArg);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
            }
            if (!presentSkip) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, SKIP_METHOD_NAME, SKIP_METHOD_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInvokeDynamicInsn(SKIP_METHOD_NAME, kind.skipIndyDescriptor, kind.skipBootstrap, bsmArg);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
            }
            if (!presentCan) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, CAN_METHOD_NAME, CAN_METHOD_DESC, null, null);
                mv.visitCode();
                mv.visitInvokeDynamicInsn(CAN_METHOD_NAME, CAN_METHOD_DESC, kind.canBootstrap, bsmArg);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
            }
            if (!presentCreate) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, CREATE_METHOD_NAME, kind.createMethodDescriptor, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.FLOAD, 2);
                mv.visitInvokeDynamicInsn(CREATE_METHOD_NAME, kind.createIndyDescriptor, kind.createBootstrap, bsmArg);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();
            }
            super.visitEnd();
        }
    }

    private enum Kind {
        NONE(null, null, null, null, null, null, null, null),
        BE(BE_VISUALIZER_DESC, BE_BOOTSTRAP,
                "(L" + TE_INTERNAL + ";)Z", BE_SKIP_BOOTSTRAP,
                BE_CAN_BOOTSTRAP,
                "(L" + VIS_CONTEXT_INTERNAL + ";F)L" + BE_VISUAL_INTERNAL + ";",
                "(L" + VIS_CONTEXT_INTERNAL + ";L" + TE_INTERNAL + ";F)L" + BE_VISUAL_INTERNAL + ";",
                BE_CREATE_BOOTSTRAP),
        ENTITY(ENTITY_VISUALIZER_DESC, ENTITY_BOOTSTRAP,
                "(L" + ENTITY_INTERNAL + ";)Z", ENTITY_SKIP_BOOTSTRAP,
                ENTITY_CAN_BOOTSTRAP,
                "(L" + VIS_CONTEXT_INTERNAL + ";F)L" + ENTITY_VISUAL_INTERNAL + ";",
                "(L" + VIS_CONTEXT_INTERNAL + ";L" + ENTITY_INTERNAL + ";F)L" + ENTITY_VISUAL_INTERNAL + ";",
                ENTITY_CREATE_BOOTSTRAP);

        final String descriptor;
        final Handle bootstrap;
        final String skipIndyDescriptor;
        final Handle skipBootstrap;
        final Handle canBootstrap;
        final String createMethodDescriptor;
        // The indy takes `this` as the middle argument; the injected method itself does not.
        final String createIndyDescriptor;
        final Handle createBootstrap;

        Kind(String descriptor, Handle bootstrap, String skipIndyDescriptor, Handle skipBootstrap,
             Handle canBootstrap, String createMethodDescriptor, String createIndyDescriptor,
             Handle createBootstrap) {
            this.descriptor = descriptor;
            this.bootstrap = bootstrap;
            this.skipIndyDescriptor = skipIndyDescriptor;
            this.skipBootstrap = skipBootstrap;
            this.canBootstrap = canBootstrap;
            this.createMethodDescriptor = createMethodDescriptor;
            this.createIndyDescriptor = createIndyDescriptor;
            this.createBootstrap = createBootstrap;
        }
    }
}
