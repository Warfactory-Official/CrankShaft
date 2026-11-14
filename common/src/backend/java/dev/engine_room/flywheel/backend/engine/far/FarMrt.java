package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/**
 * Extra surface attachments on a far pass's own colour/depth framebuffer. Render thread only; attach after the
 * pass's priming draw, since 26.2 reinstalls its declared draw buffers on every draw. Close before the pass closes
 * or another priming draw, restoring the framebuffer's single colour target for Blaze3D's framebuffer cache.
 */
public final class FarMrt implements AutoCloseable {
    private static final int[] BAKE_TARGETS = {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1,
            GL11C.GL_NONE, GL30C.GL_COLOR_ATTACHMENT3};
    private static final int[] SURFACE_TARGETS = {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1,
            GL30C.GL_COLOR_ATTACHMENT2, GL30C.GL_COLOR_ATTACHMENT3};

    private final int framebuffer;
    private final int targets;
    private final int savedState;

    private FarMrt(int framebuffer, int normal, int light, int material) {
        this.framebuffer = framebuffer;
        targets = 3;
        int saved = 0;
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            for (int i = 1; i <= targets; i++) {
                boolean blend = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, i, mask);
                int writeMask = (mask.get(0) != 0 ? 1 : 0) | (mask.get(1) != 0 ? 2 : 0)
                        | (mask.get(2) != 0 ? 4 : 0) | (mask.get(3) != 0 ? 8 : 0);
                saved |= (writeMask | (blend ? 16 : 0)) << ((i - 1) * 5);
                // Pack GL calls may have changed the actual state since Blaze3D last installed it.
                blend(i, blend);
                GlStateManager._disableBlend(i);
                GlStateManager._colorMask(i, writeMask);
                GlStateManager._colorMask(i, 15);
            }
        }
        savedState = saved;
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT1, normal, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT2, light, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT3, material, 0);
        GL45C.glNamedFramebufferDrawBuffers(framebuffer, light == 0 ? BAKE_TARGETS : SURFACE_TARGETS);
    }

    /**
     * Adds normal, material and, when nonzero, light targets to the currently bound far-pass framebuffer.
     */
    public static FarMrt attach(int normal, int light, int material) {
        return attach(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), normal, light, material);
    }

    /**
     * Adds targets to the caller's already-bound, owned framebuffer inside its borrowed GL scope.
     */
    public static FarMrt attach(int framebuffer, int normal, int light, int material) {
        return new FarMrt(framebuffer, normal, light, material);
    }

    @Override
    public void close() {
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL30C.GL_COLOR_ATTACHMENT0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT1, 0, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT2, 0, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT3, 0, 0);
        for (int i = 1; i <= targets; i++) {
            int state = savedState >>> ((i - 1) * 5);
            blend(i, (state & 16) != 0);
            GlStateManager._colorMask(i, state & 15);
        }
    }

    private static void blend(int index, boolean enabled) {
        if (enabled) {
            GlStateManager._enableBlend(index);
        } else {
            GlStateManager._disableBlend(index);
        }
    }
}
