package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GuestOitTargets {
    public static final boolean SUPPORTED = GlCompat.CAPABILITIES != null
            && GlCompat.CAPABILITIES.glCreateTextures != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glCreateFramebuffers != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glNamedFramebufferTextureLayer != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glClearNamedFramebufferfv != MemoryUtil.NULL;

    private static final float[] DEPTH_RANGE_CLEAR = {-1e30f, -1e30f, 0f, 0f};
    private static final float[] ZERO = {0f, 0f, 0f, 0f};

    private final int[] ranks;
    private final int[] accumulateFormats;

    private int width = -1;
    private int height = -1;
    private int depthRange;
    private int[] coefficients = new int[0];
    private int[] accumulate = new int[0];
    private int depthRangeFbo;
    private int coefficientsFbo;
    private int accumulateFbo;
    private int coefficientAttachments;
    private boolean allocated;

    GuestOitTargets(int[] ranks, int[] accumulateFormats) {
        this.ranks = ranks;
        this.accumulateFormats = accumulateFormats;
        for (int rank : ranks) {
            coefficientAttachments += layers(rank);
        }
    }

    /**
     * Coefficient texture layers (and producer outputs) of a rank.
     */
    static int layers(int rank) {
        return (1 << (rank + 1)) / 4;
    }

    int[] ranks() {
        return ranks;
    }

    /**
     * Resizes to {@code depth}, attaches it and clears every target.
     */
    public void prepare(GpuTexture depth, int width, int height) {
        if (width != this.width || height != this.height) {
            delete();
            allocate(width, height);
            allocated = true;
        }
        int depthId = ((GlTexture) depth).glId();
        GL45C.glNamedFramebufferTexture(depthRangeFbo, GL30C.GL_DEPTH_ATTACHMENT, depthId, 0);
        GL45C.glNamedFramebufferTexture(accumulateFbo, GL30C.GL_DEPTH_ATTACHMENT, depthId, 0);
        if (coefficientsFbo != 0) {
            GL45C.glNamedFramebufferTexture(coefficientsFbo, GL30C.GL_DEPTH_ATTACHMENT, depthId, 0);
        }
        if (allocated) {
            allocated = false;
            requireComplete(depthRangeFbo, "depth range");
            requireComplete(accumulateFbo, "accumulate");
            if (coefficientsFbo != 0) {
                requireComplete(coefficientsFbo, "coefficients");
            }
        }

        // Named clears honour the color mask and scissor.
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        GlStateManager._disableScissorTest();
        GL45C.glClearNamedFramebufferfv(depthRangeFbo, GL11C.GL_COLOR, 0, DEPTH_RANGE_CLEAR);
        for (int i = 0; i < coefficientAttachments; i++) {
            GL45C.glClearNamedFramebufferfv(coefficientsFbo, GL11C.GL_COLOR, i, ZERO);
        }
        for (int i = 0; i < accumulate.length; i++) {
            GL45C.glClearNamedFramebufferfv(accumulateFbo, GL11C.GL_COLOR, i, ZERO);
        }
    }

    private void allocate(int width, int height) {
        this.width = width;
        this.height = height;

        depthRange = texture2d(GL30C.GL_RGBA32F);
        depthRangeFbo = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(depthRangeFbo, GL30C.GL_COLOR_ATTACHMENT0, depthRange, 0);
        GL45C.glNamedFramebufferDrawBuffer(depthRangeFbo, GL30C.GL_COLOR_ATTACHMENT0);

        if (coefficientAttachments > 0) {
            coefficients = new int[ranks.length];
            coefficientsFbo = GL45C.glCreateFramebuffers();
            int[] drawBuffers = new int[coefficientAttachments];
            int attachment = 0;
            for (int i = 0; i < ranks.length; i++) {
                int layers = layers(ranks[i]);
                coefficients[i] = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY);
                GL45C.glTextureStorage3D(coefficients[i], 1, GL30C.GL_RGBA16F, width, height, layers);
                for (int layer = 0; layer < layers; layer++) {
                    GL45C.glNamedFramebufferTextureLayer(coefficientsFbo, GL30C.GL_COLOR_ATTACHMENT0 + attachment,
                            coefficients[i], 0, layer);
                    drawBuffers[attachment] = GL30C.GL_COLOR_ATTACHMENT0 + attachment;
                    attachment++;
                }
            }
            GL45C.glNamedFramebufferDrawBuffers(coefficientsFbo, drawBuffers);
        }

        accumulate = new int[accumulateFormats.length];
        accumulateFbo = GL45C.glCreateFramebuffers();
        int[] drawBuffers = new int[accumulate.length];
        for (int i = 0; i < accumulate.length; i++) {
            accumulate[i] = texture2d(accumulateFormats[i]);
            GL45C.glNamedFramebufferTexture(accumulateFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, accumulate[i], 0);
            drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        }
        GL45C.glNamedFramebufferDrawBuffers(accumulateFbo, drawBuffers);
    }

    private static void requireComplete(int fbo, String name) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("OIT " + name + " framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private int texture2d(int format) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, width, height);
        return texture;
    }

    int depthRangeFbo() {
        return depthRangeFbo;
    }

    int coefficientsFbo() {
        return coefficientsFbo;
    }

    int accumulateFbo() {
        return accumulateFbo;
    }

    int depthRange() {
        return depthRange;
    }

    int coefficients(int set) {
        return coefficients[set];
    }

    int accumulate(int slot) {
        return accumulate[slot];
    }

    // GlStateManager variants: its 2D-bind and FBO caches must not keep a deleted (reusable) name.
    void delete() {
        if (width < 0) {
            return;
        }
        for (int texture : accumulate) {
            GlStateManager._deleteTexture(texture);
        }
        GlStateManager._deleteTexture(depthRange);
        GL11C.glDeleteTextures(coefficients);
        GlStateManager._glDeleteFramebuffers(depthRangeFbo);
        GlStateManager._glDeleteFramebuffers(accumulateFbo);
        if (coefficientsFbo != 0) {
            GlStateManager._glDeleteFramebuffers(coefficientsFbo);
        }
        coefficients = new int[0];
        accumulate = new int[0];
        coefficientsFbo = 0;
        width = height = -1;
    }
}
