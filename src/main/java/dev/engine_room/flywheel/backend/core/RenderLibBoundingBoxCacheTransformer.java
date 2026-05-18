package dev.engine_room.flywheel.backend.core;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.*;
import top.outlands.foundation.IExplicitTransformer;

/**
 * RenderLib runs a per-frame bbox-cache update over every loaded entity/TE; the cache is dead
 * state for visualized objects. Route each {@code IBoundingBoxCache.updateCachedBoundingBox}
 * call inside RenderLib's bbox-cache mixin through {@code VisualizationHelper.updateCachedBoundingBox*}.
 */
public class RenderLibBoundingBoxCacheTransformer implements IExplicitTransformer {

    private static final String CACHE = "meldexun/renderlib/api/IBoundingBoxCache";
    private static final String UPDATE = "updateCachedBoundingBox";
    private static final String UPDATE_DESC = "(D)V";
    private static final String HELPER = "dev/engine_room/flywheel/lib/visualization/VisualizationHelper";
    private static final String HELPER_DESC = "(Lmeldexun/renderlib/api/IBoundingBoxCache;D)V";
    private static final String HELPER_ENTITY = "updateCachedBoundingBoxEntity";
    private static final String HELPER_TILE = "updateCachedBoundingBoxTile";
    private static final String TE_PARAM_MARKER = "Lnet/minecraft/tileentity/TileEntity;";

    @Override
    public byte[] transform(byte[] basicClass) {
        ClassReader cr = new ClassReader(basicClass);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new Rewriter(cw), 0);
        return cw.toByteArray();
    }

    private static final class Rewriter extends ClassVisitor {
        Rewriter(ClassVisitor classVisitor) {
            super(Opcodes.ASM9, classVisitor);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (mv == null) {
                return null;
            }
            boolean tileEntityContext = desc != null && desc.contains(TE_PARAM_MARKER);
            return new MethodRewriter(mv, tileEntityContext);
        }
    }

    private static final class MethodRewriter extends MethodVisitor {
        private final boolean tileEntityContext;

        MethodRewriter(MethodVisitor methodVisitor, boolean tileEntityContext) {
            super(Opcodes.ASM9, methodVisitor);
            this.tileEntityContext = tileEntityContext;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
            if (opcode == Opcodes.INVOKEINTERFACE
                    && CACHE.equals(owner)
                    && UPDATE.equals(name)
                    && UPDATE_DESC.equals(desc)) {
                String helper = tileEntityContext ? HELPER_TILE : HELPER_ENTITY;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, helper, HELPER_DESC, false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
        }
    }
}
