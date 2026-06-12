package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class FogShaders {
    /**
     * Do not apply fog.
     */
    public static final FogShader NONE = new SimpleFogShader(ResourceUtil.rl("fog/none.glsl"));
    /**
     * Blend toward the fog color with distance (26.2 native {@code apply_fog} -- the default; matches
     * vanilla-lit geometry).
     */
    public static final FogShader LINEAR = new SimpleFogShader(ResourceUtil.rl("fog/linear.glsl"));
    /**
     * Fade to fully transparent with render distance rather than blending toward the fog color. Only
     * meaningful on a {@code TRANSPARENT} material (it multiplies alpha). 26.2 has no native equivalent.
     */
    public static final FogShader LINEAR_FADE = new SimpleFogShader(ResourceUtil.rl("fog/linear_fade.glsl"));

    private FogShaders() {
    }
}
