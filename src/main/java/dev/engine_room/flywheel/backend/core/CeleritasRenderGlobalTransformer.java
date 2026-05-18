package dev.engine_room.flywheel.backend.core;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.*;
import top.outlands.foundation.IExplicitTransformer;

/**
 * Celeritas's rewrite of {@code RenderGlobal.renderEntities} drops the for-each pattern
 * {@link RenderGlobalTransformer} matches; route the surviving
 * {@code RenderManager.shouldRender} gate through {@code VisualizationHelper.shouldRender}.
 */
public class CeleritasRenderGlobalTransformer implements IExplicitTransformer {

    private static final String RENDER_MANAGER = "net/minecraft/client/renderer/entity/RenderManager";
    private static final String SHOULD_RENDER_MCP = "shouldRender";
    private static final String SHOULD_RENDER_SRG = "func_178635_a";
    private static final String SHOULD_RENDER_DESC = "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z";
    private static final String HELPER = "dev/engine_room/flywheel/lib/visualization/VisualizationHelper";
    private static final String HELPER_DESC = "(Lnet/minecraft/client/renderer/entity/RenderManager;Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z";

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
            return mv == null ? null : new MethodRewriter(mv);
        }
    }

    private static final class MethodRewriter extends MethodVisitor {
        MethodRewriter(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && RENDER_MANAGER.equals(owner)
                    && SHOULD_RENDER_DESC.equals(desc)
                    && (SHOULD_RENDER_MCP.equals(name) || SHOULD_RENDER_SRG.equals(name))) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, SHOULD_RENDER_MCP, HELPER_DESC, false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, isInterface);
        }
    }
}
