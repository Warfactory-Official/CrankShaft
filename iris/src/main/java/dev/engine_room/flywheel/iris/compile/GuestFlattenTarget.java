package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.util.stream.IntStream;

/**
 * {@link GuestParticleFlatten}'s premultiplied cloud composite and nearest kept instance, over a copy of the main
 * depth.
 */
final class GuestFlattenTarget {
    static final boolean SUPPORTED = GlCompat.CAPABILITIES != null
            && GlCompat.CAPABILITIES.glCreateTextures != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glCreateFramebuffers != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glClearNamedFramebufferfv != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glCopyImageSubData != MemoryUtil.NULL;
    static final boolean DEMOTE = SUPPORTED && IntStream.range(0, GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS))
                                                        .mapToObj(i -> GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i))
                                                        .anyMatch("GL_EXT_demote_to_helper_invocation"::equals);
    static final boolean QUAD_REJECTION = DEMOTE && GlCompat.CAPABILITIES.GL_ARB_derivative_control;
    private static final float[] ZERO = {0f, 0f, 0f, 0f};
    private static final float[] NO_ID = {-1f, 0f, 0f, 0f};

    private int width = -1;
    private int height = -1;
    private int depthFormat;
    private int texture;
    private int ids;
    private int depth;
    private int fbo;

    /**
     * Resizes to {@code main}, copies its depth and clears the composite and ids.
     */
    void prepare(GpuTexture main, int width, int height) {
        int mainDepth = ((GlTexture) main).glId();
        int format = GL45C.glGetTextureLevelParameteri(mainDepth, 0, GL30C.GL_TEXTURE_INTERNAL_FORMAT);
        if (width != this.width || height != this.height || format != depthFormat) {
            delete();
            this.width = width;
            this.height = height;
            depthFormat = format;
            texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(texture, 1, GL30C.GL_RGBA16F, width, height);
            ids = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(ids, 1, GL30C.GL_R32F, width, height);
            depth = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(depth, 1, format, width, height);
            fbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_COLOR_ATTACHMENT0, texture, 0);
            GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_COLOR_ATTACHMENT1, ids, 0);
            GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
            GL45C.glNamedFramebufferDrawBuffers(fbo, new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
            int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Particle flatten framebuffer incomplete: 0x"
                        + Integer.toHexString(status));
            }
        }
        GL43C.glCopyImageSubData(mainDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, depth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                width, height, 1);
        // Named clears honour the color mask and scissor.
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        GlStateManager._disableScissorTest();
        GL45C.glClearNamedFramebufferfv(fbo, GL11C.GL_COLOR, 0, ZERO);
        GL45C.glClearNamedFramebufferfv(fbo, GL11C.GL_COLOR, 1, NO_ID);
    }

    void bind() {
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
    }

    int texture() {
        return texture;
    }

    int ids() {
        return ids;
    }

    // GlStateManager variants: its 2D-bind and FBO caches must not keep a deleted (reusable) name.
    void delete() {
        if (width < 0) return;
        GlStateManager._deleteTexture(texture);
        GlStateManager._deleteTexture(ids);
        GlStateManager._deleteTexture(depth);
        GlStateManager._glDeleteFramebuffers(fbo);
        texture = ids = depth = fbo = 0;
        width = height = -1;
    }
}
