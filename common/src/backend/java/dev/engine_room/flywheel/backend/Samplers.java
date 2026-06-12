package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.gl.GlTextureUnit;

public class Samplers {
    public static final GlTextureUnit DIFFUSE = GlTextureUnit.T0;
    public static final GlTextureUnit OVERLAY = GlTextureUnit.T1;
    public static final GlTextureUnit LIGHT = GlTextureUnit.T2;
    public static final GlTextureUnit CRUMBLING = GlTextureUnit.T3;
    public static final GlTextureUnit INSTANCE_BUFFER = GlTextureUnit.T4;
    public static final GlTextureUnit LIGHT_LUT = GlTextureUnit.T5;
    public static final GlTextureUnit LIGHT_SECTIONS = GlTextureUnit.T6;

    public static final GlTextureUnit DEPTH_RANGE = GlTextureUnit.T7;
    public static final GlTextureUnit COEFFICIENTS = GlTextureUnit.T8;
    public static final GlTextureUnit NOISE = GlTextureUnit.T9;

    // Must match layout(binding=10) in cull.glsl / downsample_first.glsl; kept off T0-9 to avoid
    // clobbering vanilla's terrain-atlas unit.
    public static final GlTextureUnit DEPTH_PYRAMID = GlTextureUnit.T10;

    // The wavelet-OIT coefficient ARRAY (OitConfig.coefficientArray()): must match layout(binding=11) in
    // oit_producer.glsl / oit_depth.frag / oit_composite.frag / meshlet translucent_frag.frag. Raw-bound
    // (the GL_TEXTURE_2D_ARRAY binding point is untracked by GlStateManager, so a raw bind cannot desync the
    // cache); kept above every unit vanilla's bind groups reach in the OIT passes and off T10 (the pyramid).
    public static final GlTextureUnit COEFFICIENTS_ARRAY = GlTextureUnit.T11;
}
