package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.FabulousOitPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * Replays vanilla's captured rain/snow columns into an open OIT producer pass -- the weather sibling of
 * {@link CloudsOitReplay}. The geometry is vanilla's own uploaded PARTICLE-format quads; rain and snow are
 * two index ranges of the same buffer differing only in the bound Sampler0 texture, mirroring
 * {@code WeatherEffectRenderer.renderWeather}.
 */
public final class WeatherOitReplay {
    // Public: the Vulkan chains bind the same textures raw.
    public static final Identifier RAIN_LOCATION = Identifier.withDefaultNamespace("textures/environment/rain.png");
    public static final Identifier SNOW_LOCATION = Identifier.withDefaultNamespace("textures/environment/snow.png");

    private WeatherOitReplay() {
    }

    /**
     * Insert-OIT path: render the captured rain/snow ONCE into the resolved weather layer with vanilla's own
     * WEATHER pipeline (plain blending; depth-write keeps the nearest surface per pixel), depth-seeded from the
     * main opaque depth -- vanilla-fabulous semantics. The insert resolve merges the pair as one sample per
     * pixel, so weather never touches the producer interlock. MUST run before any producer pass opens.
     */
    public static void prepass(CommandEncoder encoder, FabulousCaptures capture, OitFramebuffer framebuffer) {
        if (!capture.hasWeather()) {
            return;
        }
        framebuffer.prepareWeatherLayer();
        framebuffer.seedWeatherDepth(encoder,
                Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture());

        try (RenderPass pass = encoder.createRenderPass(framebuffer.weatherLayerDescriptor())) {
            pass.setRenderPipeline(RenderPipelines.WEATHER_DEPTH_WRITE);
            RenderSystem.bindDefaultUniforms(pass);
            // Camera-relative geometry: the level model-view written at capture time (see CloudsOitReplay).
            pass.setUniform("DynamicTransforms", capture.weatherTransform);
            pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            drawCaptured(pass, capture, Minecraft.getInstance().getTextureManager());
        }
    }

    public static void replay(RenderPass pass, FabulousCaptures capture, OitMode mode, OitFramebuffer framebuffer,
                              GpuTextureView lightmapView, GpuTextureView blueNoiseView, GpuSampler loSampler,
                              GpuSampler oitSampler, GpuSampler noiseSampler, TextureManager textureManager) {
        if (!capture.hasWeather()) {
            return;
        }

        pass.setRenderPipeline(FabulousOitPipelines.weatherProducer(mode));
        // Camera-relative geometry: the level model-view written at capture time (see CloudsOitReplay).
        pass.setUniform("DynamicTransforms", capture.weatherTransform);
        // The weather-OIT vertex samples the lightmap (Sampler2); Sampler0 is per-range below.
        pass.setUniform("Sampler2", lightmapView, loSampler);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);
        drawCaptured(pass, capture, textureManager);
    }

    /**
     * Exact weather on the GL insert path; {@code bindStorage} re-binds the chain's insert buffers after the
     * pipeline switch.
     */
    public static void replayInsert(RenderPass pass, FabulousCaptures capture, OitInsertMode mode, OitFrame frame,
                                    Runnable bindStorage) {
        pass.setRenderPipeline(FabulousOitPipelines.weatherMlab(mode));
        pass.setUniform("DynamicTransforms", capture.weatherTransform);
        pass.setUniform("Sampler2", frame.lightmapView(), frame.loSampler());
        bindStorage.run();
        drawCaptured(pass, capture, frame.textureManager());
    }

    private static void drawCaptured(RenderPass pass, FabulousCaptures capture, TextureManager textureManager) {
        pass.setVertexBuffer(0, capture.weatherVertices.slice());
        // Pre-sized at capture time; re-fetched here like CloudsOitReplay.
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(indices.getBuffer(6 * (capture.rainColumns + capture.snowColumns)), indices.type());
        drawColumns(pass, textureManager.getTexture(RAIN_LOCATION), 0, capture.rainColumns);
        drawColumns(pass, textureManager.getTexture(SNOW_LOCATION), capture.rainColumns, capture.snowColumns);
    }

    private static void drawColumns(RenderPass pass, AbstractTexture texture, int startColumn, int columnCount) {
        if (columnCount == 0) {
            return;
        }
        pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
        pass.drawIndexed(columnCount * 6, 1, startColumn * 6, 0, 0);
    }
}
