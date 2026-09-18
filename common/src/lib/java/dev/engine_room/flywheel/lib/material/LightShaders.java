package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class LightShaders {
    public static final LightShader NONE = new SimpleLightShader(ResourceUtil.rl("light/none.glsl"));
    public static final LightShader SMOOTH_WHEN_EMBEDDED = new SimpleLightShader(
            ResourceUtil.rl("light/smooth_when_embedded.glsl"));
    public static final LightShader SMOOTH = new SimpleLightShader(ResourceUtil.rl("light/smooth.glsl"));
    /**
     * Actual-mesh AO, with two-block reach, 64 samples and 0.0001-block surface bias.
     * Occluders are registered independently through VisualizationContext.geometryOcclusion().
     * Receiving visuals implement GeometryLightVisual to request block/sky light and surrounding terrain.
     * Receiver colors must not already contain world AO; this replaces the stock AO multiplier.
     * A custom light shader can call flw_geometryOcclusion with different reach or quality parameters.
     */
    public static final LightShader GEOMETRY = new SimpleLightShader(ResourceUtil.rl("light/geometry.glsl"));
    public static final LightShader FLAT = new SimpleLightShader(ResourceUtil.rl("light/flat.glsl"));

    private LightShaders() {
    }
}
