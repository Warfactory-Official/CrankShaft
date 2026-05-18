package dev.engine_room.flywheel.backend.core;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.*;
import top.outlands.foundation.IExplicitTransformer;

/**
 * Targets {@code me.jellysquid.mods.sodium.mixin.features.chunk_rendering.MixinRenderGlobal}
 * (Neonium/Vintagium/Relictium share the FQN). Sodium-fork's rewrite of {@code renderEntities}
 * drops the for-each pattern {@link RenderGlobalTransformer} matches; route the first
 * surviving {@code Entity.shouldRenderInPass} through {@code VisualizationHelper.shouldRenderInPass}.
 */
public class SodiumRenderGlobalTransformer implements IExplicitTransformer {

    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String SHOULD_RENDER_IN_PASS = "shouldRenderInPass";
    private static final String SHOULD_RENDER_IN_PASS_DESC = "(I)Z";
    private static final String HELPER = "dev/engine_room/flywheel/lib/visualization/VisualizationHelper";
    private static final String HELPER_DESC = "(Lnet/minecraft/entity/Entity;I)Z";

    @Override
    public byte[] transform(byte[] basicClass) {
        ClassReader cr = new ClassReader(basicClass);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new Rewriter(cw), 0);
        return cw.toByteArray();
    }

    private static final class Rewriter extends ClassVisitor {
        private boolean rewritten;

        Rewriter(ClassVisitor classVisitor) {
            super(Opcodes.ASM9, classVisitor);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            return mv == null ? null : new MethodRewriter(mv);
        }

        private final class MethodRewriter extends MethodVisitor {
            MethodRewriter(MethodVisitor methodVisitor) {
                super(Opcodes.ASM9, methodVisitor);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                if (!rewritten
                        && opcode == Opcodes.INVOKEVIRTUAL
                        && ENTITY.equals(owner)
                        && SHOULD_RENDER_IN_PASS.equals(name)
                        && SHOULD_RENDER_IN_PASS_DESC.equals(desc)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, SHOULD_RENDER_IN_PASS, HELPER_DESC, false);
                    rewritten = true;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, desc, isInterface);
            }
        }
    }
}
