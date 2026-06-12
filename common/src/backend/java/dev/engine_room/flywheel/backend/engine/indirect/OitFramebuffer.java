package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.gl.GlLayerTexture;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.VkLayerTextureView;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.*;

import java.util.Optional;
import java.util.OptionalDouble;

public class OitFramebuffer {
    public static final GpuFormat DEPTH_BOUNDS_FORMAT = GpuFormat.RGBA32_FLOAT;
    public static final GpuFormat COEFFICIENTS_FORMAT = GpuFormat.RGBA16_FLOAT;
    public static final GpuFormat ACCUMULATE_FORMAT = GpuFormat.RGBA16_FLOAT;

    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final long BYTES_PER_PIXEL = 16L + 8L * 4L + 8L;
    private final GpuTexture[] coefficients = new GpuTexture[4];
    private final GpuTextureView[] coefficientsView = new GpuTextureView[4];
    private GpuTexture depthBounds;
    private GpuTexture accumulate;
    private int glCoefficientsArrayId;
    private GpuTexture coefficientsArray;
    private GpuTextureView depthBoundsView;
    private GpuTextureView accumulateView;

    private GpuTexture cloudsColor;
    private GpuTexture cloudsDepth;
    private GpuTextureView cloudsColorView;
    private GpuTextureView cloudsDepthView;
    private long cloudsBytes;

    private GpuTexture weatherColor;
    private GpuTexture weatherDepth;
    private GpuTextureView weatherColorView;
    private GpuTextureView weatherDepthView;
    private long weatherBytes;

    private int lastWidth = -1;
    private int lastHeight = -1;
    private long trackedBytes;

    public OitFramebuffer() {
    }

    private static int createGlCoefficientsArray(int width, int height) {
        int id = GlStateManager._genTexture();
        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, id);
        GL42C.glTexStorage3D(GL30C.GL_TEXTURE_2D_ARRAY, 1, GL30C.GL_RGBA16F, width, height, 4);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, 0);
        return id;
    }

    public void prepare() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        maybeResize(target.width, target.height);
        ensureChainTargets();
    }

    public void prepareLayersOnly() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        maybeResize(target.width, target.height);
    }

    public void releaseChainTargets() {
        deleteChainTargets();
    }

    public GpuTextureView depthBoundsView() {
        return depthBoundsView;
    }

    public GpuTextureView coefficientsView(int layer) {
        return coefficientsView[layer];
    }

    public void bindOitReads(RenderPass pass, OitMode mode, GpuTextureView blueNoiseView, GpuSampler oitSampler,
                             GpuSampler noiseSampler) {
        if (mode == OitMode.DEPTH_RANGE) {
            return;
        }
        pass.bindTexture("_flw_depthRange", depthBoundsView, oitSampler);
        pass.bindTexture("_flw_blueNoise", blueNoiseView, noiseSampler);
        if (mode == OitMode.EVALUATE) {
            bindCoefficients(pass, oitSampler);
        }
    }

    public void bindCoefficients(RenderPass pass, GpuSampler sampler) {
        if (glCoefficientsArrayId != 0) {
            bindCoefficientsArrayRaw();
            return;
        }
        pass.bindTexture("_flw_coefficients0", coefficientsView[0], sampler);
        pass.bindTexture("_flw_coefficients1", coefficientsView[1], sampler);
        pass.bindTexture("_flw_coefficients2", coefficientsView[2], sampler);
        pass.bindTexture("_flw_coefficients3", coefficientsView[3], sampler);
    }

    public void bindCoefficientsArrayRaw() {
        Samplers.COEFFICIENTS_ARRAY.makeActive();
        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, glCoefficientsArrayId);
        GL33C.glBindSampler(Samplers.COEFFICIENTS_ARRAY.number, 0);
    }

    public GpuTextureView accumulateView() {
        return accumulateView;
    }

    public void prepareCloudsLayer() {
        if (cloudsColor != null) {
            return;
        }
        var device = RenderSystem.getDevice();
        cloudsColor = device.createTexture(() -> "flywheel:oit/clouds_color", USAGE, GpuFormat.RGBA8_UNORM, lastWidth,
                lastHeight, 1, 1);
        cloudsColorView = device.createTextureView(cloudsColor);
        cloudsDepth = device.createTexture(() -> "flywheel:oit/clouds_depth", USAGE, GpuFormat.D32_FLOAT, lastWidth,
                lastHeight, 1, 1);
        cloudsDepthView = device.createTextureView(cloudsDepth);
        cloudsBytes = (long) lastWidth * lastHeight * 8L;
        FlwMemoryTracker._allocGpuMemory(cloudsBytes);
    }

    public GpuTextureView cloudsColorView() {
        return cloudsColorView;
    }

    public GpuTextureView cloudsDepthView() {
        return cloudsDepthView;
    }

    public void seedCloudsDepth(CommandEncoder encoder, GpuTexture mainDepth) {
        encoder.copyTextureToTexture(mainDepth, cloudsDepth, 0, 0, 0, 0, 0, lastWidth, lastHeight);
    }

    public RenderPassDescriptor cloudsLayerDescriptor() {
        return RenderPassDescriptor.create(() -> "flywheel:oit/clouds_layer")
                                   .withColorAttachment(cloudsColorView,
                                           Optional.<Vector4fc>of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))
                                   .withDepthAttachment(cloudsDepthView, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    public void prepareWeatherLayer() {
        if (weatherColor != null) {
            return;
        }
        var device = RenderSystem.getDevice();
        weatherColor = device.createTexture(() -> "flywheel:oit/weather_color", USAGE, GpuFormat.RGBA8_UNORM, lastWidth,
                lastHeight, 1, 1);
        weatherColorView = device.createTextureView(weatherColor);
        weatherDepth = device.createTexture(() -> "flywheel:oit/weather_depth", USAGE, GpuFormat.D32_FLOAT, lastWidth,
                lastHeight, 1, 1);
        weatherDepthView = device.createTextureView(weatherDepth);
        weatherBytes = (long) lastWidth * lastHeight * 8L;
        FlwMemoryTracker._allocGpuMemory(weatherBytes);
    }

    public GpuTextureView weatherColorView() {
        return weatherColorView;
    }

    public GpuTextureView weatherDepthView() {
        return weatherDepthView;
    }

    public void seedWeatherDepth(CommandEncoder encoder, GpuTexture mainDepth) {
        encoder.copyTextureToTexture(mainDepth, weatherDepth, 0, 0, 0, 0, 0, lastWidth, lastHeight);
    }

    public RenderPassDescriptor weatherLayerDescriptor() {
        return RenderPassDescriptor.create(() -> "flywheel:oit/weather_layer")
                                   .withColorAttachment(weatherColorView,
                                           Optional.<Vector4fc>of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))
                                   .withDepthAttachment(weatherDepthView, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    public RenderPassDescriptor depthRangeDescriptor(GpuTextureView mainDepth, float far) {
        return RenderPassDescriptor.create(() -> "flywheel:oit/depth_range")
                                   .withColorAttachment(depthBoundsView,
                                           Optional.<Vector4fc>of(new Vector4f(-far, -far, 0.0f, 0.0f)))
                                   .withDepthAttachment(mainDepth, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    public RenderPassDescriptor coefficientsDescriptor(GpuTextureView mainDepth) {
        Optional<Vector4fc> zero = Optional.<Vector4fc>of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        return RenderPassDescriptor.create(() -> "flywheel:oit/coefficients")
                                   .withColorAttachment(coefficientsView[0], zero)
                                   .withColorAttachment(coefficientsView[1], zero)
                                   .withColorAttachment(coefficientsView[2], zero)
                                   .withColorAttachment(coefficientsView[3], zero)
                                   .withDepthAttachment(mainDepth, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    public RenderPassDescriptor depthFromTransmittanceDescriptor(GpuTextureView mainDepth) {
        return RenderPassDescriptor.create(() -> "flywheel:oit/depth_from_transmittance")
                                   .withColorAttachment(accumulateView)
                                   .withDepthAttachment(mainDepth, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    public RenderPassDescriptor accumulateDescriptor(GpuTextureView mainDepth) {
        return RenderPassDescriptor.create(() -> "flywheel:oit/accumulate")
                                   .withColorAttachment(accumulateView,
                                           Optional.<Vector4fc>of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))
                                   .withDepthAttachment(mainDepth, OptionalDouble.empty())
                                   .withRenderArea(fullArea());
    }

    private RenderPass.RenderArea fullArea() {
        return new RenderPass.RenderArea(0, 0, lastWidth, lastHeight);
    }

    public void delete() {
        deleteTargets();
    }

    private void maybeResize(int width, int height) {
        if (lastWidth == width && lastHeight == height) {
            return;
        }
        lastWidth = width;
        lastHeight = height;
        deleteTargets();
    }

    private void ensureChainTargets() {
        if (depthBounds != null) {
            return;
        }
        var device = RenderSystem.getDevice();
        depthBounds = device.createTexture(() -> "flywheel:oit/depth_bounds", USAGE, DEPTH_BOUNDS_FORMAT, lastWidth,
                lastHeight, 1, 1);
        depthBoundsView = device.createTextureView(depthBounds);
        if (VkContext.isVulkanHost()) {
            coefficientsArray = new VulkanGpuTexture(VkContext.device(), USAGE, "flywheel:oit/coefficients",
                    COEFFICIENTS_FORMAT, lastWidth, lastHeight, 4, 1);
            for (int i = 0; i < 4; i++) {
                coefficientsView[i] = VkLayerTextureView.create(coefficientsArray, i);
            }
        } else if (OitConfig.coefficientArray()) {
            glCoefficientsArrayId = createGlCoefficientsArray(lastWidth, lastHeight);
            for (int i = 0; i < 4; i++) {
                int viewId = GlStateManager._genTexture();
                GL43C.glTextureView(viewId, GL11C.GL_TEXTURE_2D, glCoefficientsArrayId, GL30C.GL_RGBA16F, 0, 1, i, 1);
                coefficients[i] = GlLayerTexture.wrap(viewId, "flywheel:oit/coefficients" + i, COEFFICIENTS_FORMAT,
                        lastWidth, lastHeight);
                coefficientsView[i] = device.createTextureView(coefficients[i]);
            }
        } else {
            for (int i = 0; i < 4; i++) {
                int layer = i;
                coefficients[i] = device.createTexture(() -> "flywheel:oit/coefficients" + layer, USAGE,
                        COEFFICIENTS_FORMAT, lastWidth, lastHeight, 1, 1);
                coefficientsView[i] = device.createTextureView(coefficients[i]);
            }
        }
        accumulate = device.createTexture(() -> "flywheel:oit/accumulate", USAGE, ACCUMULATE_FORMAT, lastWidth,
                lastHeight, 1, 1);
        accumulateView = device.createTextureView(accumulate);

        trackedBytes = (long) lastWidth * lastHeight * BYTES_PER_PIXEL;
        FlwMemoryTracker._allocGpuMemory(trackedBytes);
    }

    private void deleteChainTargets() {
        if (depthBoundsView != null) {
            depthBoundsView.close();
            depthBoundsView = null;
        }
        if (depthBounds != null) {
            depthBounds.close();
            depthBounds = null;
        }
        for (int i = 0; i < 4; i++) {
            if (coefficientsView[i] != null) {
                coefficientsView[i].close();
                coefficientsView[i] = null;
            }
            if (coefficients[i] != null) {
                coefficients[i].close();
                coefficients[i] = null;
            }
        }
        if (coefficientsArray != null) {
            coefficientsArray.close();
            coefficientsArray = null;
        }
        if (glCoefficientsArrayId != 0) {
            GlStateManager._deleteTexture(glCoefficientsArrayId);
            glCoefficientsArrayId = 0;
        }
        if (accumulateView != null) {
            accumulateView.close();
            accumulateView = null;
        }
        if (accumulate != null) {
            accumulate.close();
            accumulate = null;
        }
        FlwMemoryTracker._freeGpuMemory(trackedBytes);
        trackedBytes = 0;
    }

    private void deleteTargets() {
        deleteChainTargets();
        if (cloudsColorView != null) {
            cloudsColorView.close();
            cloudsColorView = null;
        }
        if (cloudsColor != null) {
            cloudsColor.close();
            cloudsColor = null;
        }
        if (cloudsDepthView != null) {
            cloudsDepthView.close();
            cloudsDepthView = null;
        }
        if (cloudsDepth != null) {
            cloudsDepth.close();
            cloudsDepth = null;
        }
        FlwMemoryTracker._freeGpuMemory(cloudsBytes);
        cloudsBytes = 0;

        if (weatherColorView != null) {
            weatherColorView.close();
            weatherColorView = null;
        }
        if (weatherColor != null) {
            weatherColor.close();
            weatherColor = null;
        }
        if (weatherDepthView != null) {
            weatherDepthView.close();
            weatherDepthView = null;
        }
        if (weatherDepth != null) {
            weatherDepth.close();
            weatherDepth = null;
        }
        FlwMemoryTracker._freeGpuMemory(weatherBytes);
        weatherBytes = 0;
    }
}
