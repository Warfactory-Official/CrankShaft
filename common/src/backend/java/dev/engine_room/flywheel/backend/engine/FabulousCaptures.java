package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.jspecify.annotations.Nullable;

/**
 * Captured vanilla clouds + weather geometry and the resolved item-entity/particle layers for the Improved
 * Transparency reroute: instead of vanilla's transparency post-chain layer-sorting five screen-sized targets,
 * the same content replays into the flywheel OIT producer passes ({@link LayerOitReplay} /
 * {@link WeatherOitReplay}) and vanilla's own clouds/weather draws are suppressed. Filled once per frame at
 * the OIT seam: clouds/weather by invoking the vanilla renderers in capture mode (their mixins cancel right
 * before the vanilla RenderPass opens and hand the prepared buffers here), the item/particle layer views from
 * {@link FabulousLayerTargets} (vanilla drew into them through the redirected getters); every buffer is valid
 * for the frame it was captured in. Render-thread only.
 */
public final class FabulousCaptures {
    // Clouds: CloudRenderer's prepared per-frame state (CloudInfo UBO + CloudFaces R8I face stream + quad
    // count). cloudsTransform is the level model-view written at capture time -- cloud geometry is
    // camera-relative, so the render-origin transform the instance producers use does not apply.
    public @Nullable GpuBuffer cloudInfo;
    public @Nullable GpuBuffer cloudFaces;
    public int cloudQuads;
    public boolean cloudsFancy;
    public @Nullable GpuBufferSlice cloudsTransform;

    // Weather: WeatherEffectRenderer's uploaded PARTICLE-format quads (camera-relative, like clouds); rain
    // occupies columns [0, rainColumns), snow [rainColumns, rainColumns + snowColumns).
    public @Nullable GpuBuffer weatherVertices;
    public int rainColumns;
    public int snowColumns;
    public @Nullable GpuBufferSlice weatherTransform;

    // The resolved item-entity/particle layers (color + depth views of FabulousLayerTargets): vanilla drew
    // into them through the redirected getters during the level's translucent window; non-null only for
    // layers a redirected consumer actually touched this frame.
    public @Nullable GpuTextureView itemLayerColor;
    public @Nullable GpuTextureView itemLayerDepth;
    public @Nullable GpuTextureView particleLayerColor;
    public @Nullable GpuTextureView particleLayerDepth;

    public boolean hasClouds() {
        return cloudInfo != null && cloudFaces != null && cloudQuads > 0;
    }

    public boolean hasWeather() {
        return weatherVertices != null && rainColumns + snowColumns > 0;
    }

    public boolean hasItemLayer() {
        return itemLayerColor != null && itemLayerDepth != null;
    }

    public boolean hasParticleLayer() {
        return particleLayerColor != null && particleLayerDepth != null;
    }

    public boolean hasAny() {
        return hasClouds() || hasWeather() || hasItemLayer() || hasParticleLayer();
    }

    /**
     * Which resolved Improved-Transparency layers an insert resolve merges (bit 0 clouds, 1 item, 2 particles,
     * 3 weather-when-layered). Under exactFabulous, weather stays a geometry producer, so callers pass
     * {@code weatherIsLayered = false} and the weather producer inserts instead.
     */
    public int layerMask(boolean weatherIsLayered) {
        int mask = hasClouds() ? 1 : 0;
        mask |= hasItemLayer() ? 2 : 0;
        mask |= hasParticleLayer() ? 4 : 0;
        mask |= hasWeather() && weatherIsLayered ? 8 : 0;
        return mask;
    }

    public void clear() {
        cloudInfo = null;
        cloudFaces = null;
        cloudQuads = 0;
        cloudsTransform = null;
        weatherVertices = null;
        rainColumns = 0;
        snowColumns = 0;
        weatherTransform = null;
        itemLayerColor = null;
        itemLayerDepth = null;
        particleLayerColor = null;
        particleLayerDepth = null;
    }
}
