package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.*;

public class DepthPyramid {
    private static final long PLACEHOLDER_BYTES = 4L;
    private final IndirectPrograms programs;
    public int pyramidTextureId = -1;
    private int placeholderTextureId = -1;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private long pyramidBytes;

    public DepthPyramid(IndirectPrograms programs) {
        this.programs = programs;
    }

    public static int mip0Size(int screenSize) {
        int nextPot = screenSize <= 1 ? 1 : Integer.highestOneBit(screenSize - 1) << 1;
        return Math.max(nextPot >> 1, 1);
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

    public void generate() {
        RenderTarget mcFb = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depthTexture = mcFb.getDepthTexture();
        if (depthTexture == null) {
            return;
        }
        regenerate(((GlTexture) depthTexture).glId(), mcFb.width, mcFb.height);
    }

    public void regenerate(int depthBufferTextureId, int framebufferWidth, int framebufferHeight) {
        regenerate(depthBufferTextureId, framebufferWidth, framebufferHeight, false);
    }

    public void regenerate(int depthBufferTextureId, int framebufferWidth, int framebufferHeight,
                           boolean deferFetchBarrier) {
        int width = mip0Size(framebufferWidth);
        int height = mip0Size(framebufferHeight);
        int mipLevels = getImageMipLevels(width, height);

        createPyramidMips(mipLevels, width, height);

        GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);

        Samplers.DEPTH_PYRAMID.makeActive();
        GlStateManager._bindTexture(depthBufferTextureId);

        var downsampleFirstProgram = programs.getDownsampleFirstProgram();
        downsampleFirstProgram.bind();

        GL42.glBindImageTexture(1, pyramidTextureId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        GL43.glDispatchCompute(Mth.positiveCeilDiv(width << 1, 64), Mth.positiveCeilDiv(height << 1, 64), 1);

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

            GL43.glDispatchCompute(Mth.positiveCeilDiv(width >> baseMipLevel, 64),
                    Mth.positiveCeilDiv(height >> baseMipLevel, 64), 1);
        }

        if (!deferFetchBarrier) {
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
    }

    public void bindForCull() {
        Samplers.DEPTH_PYRAMID.makeActive();
        GlStateManager._bindTexture(pyramidTextureId != -1 ? pyramidTextureId : ensurePlaceholder());
    }

    public void bindPlaceholder() {
        Samplers.DEPTH_PYRAMID.makeActive();
        GlStateManager._bindTexture(ensurePlaceholder());
    }

    private int ensurePlaceholder() {
        if (placeholderTextureId == -1) {
            placeholderTextureId = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(placeholderTextureId, 1, GL30.GL_R32F, 1, 1);
            FlwMemoryTracker._allocGpuMemory(PLACEHOLDER_BYTES);
            GL45.glTextureSubImage2D(placeholderTextureId, 0, 0, 0, 1, 1,
                    GL11.GL_RED, GL11.GL_FLOAT, new float[]{0.0f});
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
            GL11.glDeleteTextures(pyramidTextureId);
            FlwMemoryTracker._freeGpuMemory(pyramidBytes);
            pyramidBytes = 0;
            pyramidTextureId = -1;
        }
        if (placeholderTextureId != -1) {
            GL11.glDeleteTextures(placeholderTextureId);
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
            GL11.glDeleteTextures(pyramidTextureId);
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
}
