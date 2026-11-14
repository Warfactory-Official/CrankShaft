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

public final class GuestOitTargets {
    public static final boolean SUPPORTED = GlCompat.CAPABILITIES != null
            && GlCompat.CAPABILITIES.glCreateTextures != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glCreateFramebuffers != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glNamedFramebufferTextureLayer != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glClearNamedFramebufferfv != MemoryUtil.NULL;

    private static final float[] DEPTH_RANGE_CLEAR = {-1e30f, -1e30f, 0f, 0f};
    private static final float[] ZERO = {0f, 0f, 0f, 0f};
    private static final int[] UINT_ZERO = {0, 0, 0, 0};

    private final int[] ranks;
    private final int[] accumulateFormats;

    private int width = -1;
    private int height = -1;
    private int storageWidth;
    private int storageHeight;
    private int depthRange;
    private int[] coefficients = new int[0];
    private int[] accumulate = new int[0];
    private int depthRangeFbo;
    private int coefficientsFbo;
    private int accumulateFbo;
    private int coefficientAttachments;
    private boolean allocated;
    private final ForwardOit forward;
    private int forwardFbo;
    private int forwardTexture;
    private int forwardBackground;
    private ForwardOit.SnapshotRect forwardSnapshot;
    private ForwardOit.SnapshotRect forwardCapture;
    private int[] forwardWater = new int[0];
    private int forwardSurfaceClaim;
    private int forwardSurfaceClaimFbo;

    GuestOitTargets(int[] ranks, int[] accumulateFormats, ForwardOit forward) {
        this.ranks = ranks;
        this.accumulateFormats = accumulateFormats;
        this.forward = forward;
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

    private static void requireComplete(int fbo, String name) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                    "OIT " + name + " framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    int[] ranks() {
        return ranks;
    }

    /**
     * Resizes to {@code depth}, attaches it and clears every target.
     */
    public void prepare(GpuTexture depth, int width, int height) {
        prepare(((GlTexture) depth).glId(), width, height);
    }

    /**
     * Borrowed host depth; ownership and lifetime remain with DH/Voxy.
     */
    void prepare(int depthId, int width, int height) {
        if (width != this.width || height != this.height) {
            delete();
            allocate(width, height);
            allocated = true;
        }
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
            if (forward != null) requireComplete(forwardSurfaceClaimFbo, "forward surface claim");
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
        if (forward != null)
            GL45C.glClearNamedFramebufferuiv(forwardSurfaceClaimFbo, GL11C.GL_COLOR, 0, UINT_ZERO);
    }

    private void allocate(int width, int height) {
        this.width = width;
        this.height = height;
        storageWidth = width;
        storageHeight = height;
        if (forward != null) {
            forwardSnapshot = forward.snapshotRect(width, height);
            forwardCapture = forward.captureRect(width, height);
            storageWidth = forwardCapture.width();
            storageHeight = forwardCapture.height();
        }

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
                GL45C.glTextureStorage3D(coefficients[i], 1, GL30C.GL_RGBA16F, storageWidth, storageHeight, layers);
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
        if (forward != null) {
            forwardSurfaceClaim = texture2d(GL30C.GL_R32UI);
            forwardSurfaceClaimFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(forwardSurfaceClaimFbo, GL30C.GL_COLOR_ATTACHMENT0,
                    forwardSurfaceClaim, 0);
            GL45C.glNamedFramebufferDrawBuffer(forwardSurfaceClaimFbo, GL30C.GL_COLOR_ATTACHMENT0);
            forwardWater = new int[forward.waterBuffers().length];
            for (int i = 0; i < forwardWater.length; i++) forwardWater[i] = texture2d(GL30C.GL_RGBA32UI);
        }
    }

    private int texture2d(int format) {
        return texture2d(format, storageWidth, storageHeight);
    }

    private int texture2d(int format, int textureWidth, int textureHeight) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, textureWidth, textureHeight);
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

    int forwardFbo() {
        return forwardFbo;
    }

    int forwardBackground() {
        return forwardBackground;
    }

    int forwardWaterCount() {
        return forward == null ? 0 : forward.waterBuffers().length;
    }

    int forwardWater(int slot) {
        return forwardWater[slot];
    }

    int forwardSurfaceClaim() {
        return forwardSurfaceClaim;
    }

    ForwardOit.SnapshotRect forwardCapture() {
        return forwardCapture;
    }

    ForwardOit.SnapshotRect forwardSnapshot() {
        return forwardSnapshot;
    }

    void forwardTarget(int texture) {
        if (forwardFbo == 0) {
            forwardFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(forwardFbo, GL30C.GL_COLOR_ATTACHMENT0);
        }
        if (forwardTexture != texture) {
            forwardTexture = texture;
            GL45C.glNamedFramebufferTexture(forwardFbo, GL30C.GL_COLOR_ATTACHMENT0, texture, 0);
            requireComplete(forwardFbo, "forward resolve");
        }
        if (forwardBackground == 0) forwardBackground = texture2d(forward.format.getGlFormat(), width, height);
        if (!forwardSnapshot.empty()) GL43C.glCopyImageSubData(texture, GL11C.GL_TEXTURE_2D, 0,
                forwardSnapshot.x(), forwardSnapshot.y(), 0, forwardBackground, GL11C.GL_TEXTURE_2D, 0,
                forwardSnapshot.x(), forwardSnapshot.y(), 0, forwardSnapshot.width(), forwardSnapshot.height(), 1);
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
        if (forwardFbo != 0) GlStateManager._glDeleteFramebuffers(forwardFbo);
        if (forwardBackground != 0) GlStateManager._deleteTexture(forwardBackground);
        if (forwardSurfaceClaim != 0) GlStateManager._deleteTexture(forwardSurfaceClaim);
        if (forwardSurfaceClaimFbo != 0) GlStateManager._glDeleteFramebuffers(forwardSurfaceClaimFbo);
        for (int texture : forwardWater) GlStateManager._deleteTexture(texture);
        forwardFbo = forwardTexture = forwardBackground = 0;
        forwardSurfaceClaim = forwardSurfaceClaimFbo = 0;
        forwardWater = new int[0];
        coefficients = new int[0];
        accumulate = new int[0];
        coefficientsFbo = 0;
        width = height = -1;
    }
}
