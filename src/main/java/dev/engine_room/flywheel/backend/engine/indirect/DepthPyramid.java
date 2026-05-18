package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

public class DepthPyramid {
    private final IndirectPrograms programs;

    public int pyramidTextureId = -1;

    /** 1×1 R32F=1.0 fallback bound when the pyramid hasn't been generated yet (e.g. first frame
     *  before mc.framebuffer is fully initialized). The cull shader's `depthSphere <= depth`
     *  test against depth=1.0 always passes, so culling degenerates to frustum-only. Without
     *  this, sampling an unbound texture returns 0 and the test would reject every instance. */
    private int placeholderTextureId = -1;

    private int lastWidth = -1;
    private int lastHeight = -1;
    private static final long PLACEHOLDER_BYTES = 4L;
    private long pyramidBytes;

    public DepthPyramid(IndirectPrograms programs) {
        this.programs = programs;
    }

    public void generate() {
        var mcFb = Minecraft.getMinecraft().getFramebuffer();
        if (mcFb == null || mcFb.depthBuffer < 0) {
            return;
        }
        regenerate(mcFb.depthBuffer, mcFb.framebufferTextureWidth, mcFb.framebufferTextureHeight);
    }

    private void regenerate(int depthBufferTextureId, int framebufferWidth, int framebufferHeight) {
        int width = mip0Size(framebufferWidth);
        int height = mip0Size(framebufferHeight);
        int mipLevels = getImageMipLevels(width, height);

        createPyramidMips(mipLevels, width, height);

        GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);

        GlTextureUnit.T0.makeActive();
        GlStateManager.bindTexture(depthBufferTextureId);

        var downsampleFirstProgram = programs.getDownsampleFirstProgram();
        downsampleFirstProgram.bind();

        GL42.glBindImageTexture(1, pyramidTextureId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute(MoreMath.ceilingDiv(width << 1, 64), MoreMath.ceilingDiv(height << 1, 64), 1);

        var downsampleSecondProgram = programs.getDownsampleSecondProgram();
        downsampleSecondProgram.bind();
        downsampleSecondProgram.setUInt("mip_levels", mipLevels);

        for (int baseMipLevel = 0; baseMipLevel + 1 < mipLevels; baseMipLevel += 6) {
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            downsampleSecondProgram.setUInt("base_mip_level", baseMipLevel);

            for (int i = 0; i < Math.min(7, mipLevels - baseMipLevel); i++) {
                GL42.glBindImageTexture(i, pyramidTextureId, baseMipLevel + i, false, 0, GL15.GL_WRITE_ONLY,
                        GL30.GL_R32F);
            }

            GL43.glDispatchCompute(MoreMath.ceilingDiv(width >> baseMipLevel, 64),
                    MoreMath.ceilingDiv(height >> baseMipLevel, 64), 1);
        }

        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    public void bindForCull() {
        GlTextureUnit.T0.makeActive();
        GlStateManager.bindTexture(pyramidTextureId != -1 ? pyramidTextureId : ensurePlaceholder());
    }

    private int ensurePlaceholder() {
        if (placeholderTextureId == -1) {
            placeholderTextureId = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(placeholderTextureId, 1, GL30.GL_R32F, 1, 1);
            FlwMemoryTracker._allocGpuMemory(PLACEHOLDER_BYTES);
            GL45.glTextureSubImage2D(placeholderTextureId, 0, 0, 0, 1, 1,
                    GL11.GL_RED, GL11.GL_FLOAT, new float[]{1.0f});
            GL45.glTextureParameteri(placeholderTextureId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(placeholderTextureId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(placeholderTextureId, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            GL45.glTextureParameteri(placeholderTextureId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(placeholderTextureId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        return placeholderTextureId;
    }

    public void delete() {
        if (pyramidTextureId != -1) {
            GlStateManager.deleteTexture(pyramidTextureId);
            FlwMemoryTracker._freeGpuMemory(pyramidBytes);
            pyramidBytes = 0;
            pyramidTextureId = -1;
        }
        if (placeholderTextureId != -1) {
            GlStateManager.deleteTexture(placeholderTextureId);
            FlwMemoryTracker._freeGpuMemory(PLACEHOLDER_BYTES);
            placeholderTextureId = -1;
        }
        lastWidth = -1;
        lastHeight = -1;
    }

    private void createPyramidMips(int mipLevels, int width, int height) {
        if (lastWidth == width && lastHeight == height) {
            return;
        }

        lastWidth = width;
        lastHeight = height;

        if (pyramidTextureId != -1) {
            GlStateManager.deleteTexture(pyramidTextureId);
            FlwMemoryTracker._freeGpuMemory(pyramidBytes);
        }

        pyramidTextureId = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(pyramidTextureId, mipLevels, GL30.GL_R32F, width, height);
        pyramidBytes = mipChainBytes(mipLevels, width, height);
        FlwMemoryTracker._allocGpuMemory(pyramidBytes);

        GL45.glTextureParameteri(pyramidTextureId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(pyramidTextureId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(pyramidTextureId, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL45.glTextureParameteri(pyramidTextureId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(pyramidTextureId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    public static int mip0Size(int screenSize) {
        return Integer.highestOneBit(screenSize);
    }

    private static long mipChainBytes(int mipLevels, int width, int height) {
        long total = 0;
        for (int i = 0; i < mipLevels; i++) {
            int w = Math.max(1, width >> i);
            int h = Math.max(1, height >> i);
            total += (long) w * h * 4L;
        }
        return total;
    }

    public static int getImageMipLevels(int width, int height) {
        int result = 1;

        while (width > 1 && height > 1) {
            result++;
            width >>= 1;
            height >>= 1;
        }

        return result;
    }
}
