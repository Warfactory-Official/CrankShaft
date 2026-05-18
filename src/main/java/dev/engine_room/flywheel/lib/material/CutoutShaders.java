package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class CutoutShaders {
    /**
     * Do not discard any fragments based on alpha.
     */
    public static final CutoutShader OFF = new SimpleCutoutShader(ResourceUtil.rl("cutout/off.glsl"));
    /**
     * Discard fragments with alpha close to or equal to zero.
     */
    public static final CutoutShader EPSILON = new SimpleCutoutShader(ResourceUtil.rl("cutout/epsilon.glsl"));
    /**
     * Discard fragments with alpha less than to 0.1.
     */
    public static final CutoutShader ONE_TENTH = new SimpleCutoutShader(ResourceUtil.rl("cutout/one_tenth.glsl"));
    /**
     * Discard fragments with alpha less than to 0.5.
     */
    public static final CutoutShader HALF = new SimpleCutoutShader(ResourceUtil.rl("cutout/half.glsl"));

    /** Two-sided slab clip in OBJ-local space. Requires the CLIP_TRANSFORMED
     *  instance type — the fragment shader reads varyings emitted by
     *  {@code instance/clip_transformed.vert}. */
    public static final CutoutShader CLIP_SLAB = new SimpleCutoutShader(ResourceUtil.rl("cutout/clip_slab.glsl"));

    /** One-sided half-space clip. Same instance-type requirement as {@link #CLIP_SLAB}. */
    public static final CutoutShader CLIP_HALFSPACE = new SimpleCutoutShader(ResourceUtil.rl("cutout/clip_halfspace.glsl"));

    private CutoutShaders() {
    }
}
