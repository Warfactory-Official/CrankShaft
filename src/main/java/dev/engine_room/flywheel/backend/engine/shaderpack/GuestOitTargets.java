package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
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
    private int depthCopy;
    private int depthFormat;
    private int[] coefficients = new int[0];
    private int[] accumulate = new int[0];
    private int depthRangeFbo;
    private int coefficientsFbo;
    private int accumulateFbo;
    private int coefficientAttachments;

    GuestOitTargets(int[] ranks, int[] accumulateFormats) {
        this.ranks = ranks;
        this.accumulateFormats = accumulateFormats;
        for (int rank : ranks) {
            coefficientAttachments += layers(rank);
        }
    }

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

    static int sizedDepthFormat(int depthId) {
        var format = GL45C.glGetTextureLevelParameteri(depthId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        // Legacy Framebuffer uses an unsized depth format, which immutable texture storage does not accept.
        if (format == GL11C.GL_DEPTH_COMPONENT) {
            format = switch (GL45C.glGetTextureLevelParameteri(depthId, 0, GL14C.GL_TEXTURE_DEPTH_SIZE)) {
                case 16 -> GL14C.GL_DEPTH_COMPONENT16;
                case 24 -> GL14C.GL_DEPTH_COMPONENT24;
                case 32 -> GL14C.GL_DEPTH_COMPONENT32;
                default -> throw new UnsupportedOperationException("Unsupported legacy depth precision");
            };
        } else if (format == GL30C.GL_DEPTH_STENCIL) format = GL30C.GL_DEPTH24_STENCIL8;
        return format;
    }

    int[] ranks() {
        return ranks;
    }

    public void prepare(int sourceFbo, int depthId, int x, int y, int width, int height) {
        var format = sizedDepthFormat(depthId);
        if (width != this.width || height != this.height || format != depthFormat) {
            delete();
            depthFormat = format;
            allocate(width, height);
        }

        var stencil = GL45C.glGetNamedFramebufferAttachmentParameteri(sourceFbo, GL30C.GL_STENCIL_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        var copyStencil = stencil == GL11C.GL_TEXTURE
                && GL45C.glGetNamedFramebufferAttachmentParameteri(sourceFbo, GL30C.GL_STENCIL_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == depthId;
        if (GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST) && !copyStencil) {
            throw new UnsupportedOperationException(
                    "Shared OIT needs a packed texture-backed stencil attachment when stencil testing is active");
        }
        // Blits and named clears honour scissor; private targets use viewport-relative coordinates.
        GlStateManager.colorMask(true, true, true, true);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL45C.glBlitNamedFramebuffer(sourceFbo, depthRangeFbo, x, y, x + width, y + height, 0, 0, width, height,
                GL11C.GL_DEPTH_BUFFER_BIT | (copyStencil ? GL11C.GL_STENCIL_BUFFER_BIT : 0), GL11C.GL_NEAREST);
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
        depthCopy = texture2d(depthFormat);
        var attachment = depthFormat == GL30C.GL_DEPTH24_STENCIL8 || depthFormat == GL30C.GL_DEPTH32F_STENCIL8
                ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
        depthRangeFbo = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(depthRangeFbo, attachment, depthCopy, 0);
        GL45C.glNamedFramebufferTexture(depthRangeFbo, GL30C.GL_COLOR_ATTACHMENT0, depthRange, 0);
        GL45C.glNamedFramebufferDrawBuffer(depthRangeFbo, GL30C.GL_COLOR_ATTACHMENT0);

        if (coefficientAttachments > 0) {
            coefficients = new int[ranks.length];
            coefficientsFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(coefficientsFbo, attachment, depthCopy, 0);
            int[] drawBuffers = new int[coefficientAttachments];
            int slot = 0;
            for (int i = 0; i < ranks.length; i++) {
                int layers = layers(ranks[i]);
                coefficients[i] = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY);
                GL45C.glTextureStorage3D(coefficients[i], 1, GL30C.GL_RGBA16F, width, height, layers);
                for (int layer = 0; layer < layers; layer++) {
                    GL45C.glNamedFramebufferTextureLayer(coefficientsFbo, GL30C.GL_COLOR_ATTACHMENT0 + slot,
                            coefficients[i], 0, layer);
                    drawBuffers[slot] = GL30C.GL_COLOR_ATTACHMENT0 + slot;
                    slot++;
                }
            }
            GL45C.glNamedFramebufferDrawBuffers(coefficientsFbo, drawBuffers);
        }

        accumulate = new int[accumulateFormats.length];
        accumulateFbo = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(accumulateFbo, attachment, depthCopy, 0);
        int[] drawBuffers = new int[accumulate.length];
        for (int i = 0; i < accumulate.length; i++) {
            accumulate[i] = texture2d(accumulateFormats[i]);
            GL45C.glNamedFramebufferTexture(accumulateFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, accumulate[i], 0);
            drawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        }
        GL45C.glNamedFramebufferDrawBuffers(accumulateFbo, drawBuffers);
        requireComplete(depthRangeFbo, "depth range");
        requireComplete(accumulateFbo, "accumulate");
        if (coefficientsFbo != 0) requireComplete(coefficientsFbo, "coefficients");
    }

    private int texture2d(int format) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, width, height);
        return texture;
    }

    int depthRangeFbo() {
        return depthRangeFbo;
    }

    int attachmentCount(OitPass pass) {
        return switch (pass) {
            case DEPTH_RANGE -> 1;
            case COEFFICIENTS -> coefficientAttachments;
            case EVALUATE -> accumulate.length;
        };
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
            GlStateManager.deleteTexture(texture);
        }
        GlStateManager.deleteTexture(depthRange);
        GlStateManager.deleteTexture(depthCopy);
        GL11C.glDeleteTextures(coefficients);
        GL30C.glDeleteFramebuffers(depthRangeFbo);
        GL30C.glDeleteFramebuffers(accumulateFbo);
        if (coefficientsFbo != 0) {
            GL30C.glDeleteFramebuffers(coefficientsFbo);
        }
        coefficients = new int[0];
        accumulate = new int[0];
        coefficientsFbo = 0;
        width = height = -1;
    }
}
