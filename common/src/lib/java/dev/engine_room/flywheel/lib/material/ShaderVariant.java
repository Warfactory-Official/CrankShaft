package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.api.material.Transparency;

import java.util.Comparator;

/**
 * The part of a {@link Material} the warm-up keys programs on. On native backends depth test, depth write, backface
 * culling, polygon offset, texture identity and the runtime property bits (blur, mipmap, texture sampler, overlay,
 * cardinal lighting, ambient occlusion) select no program: every combination draws with the programs of its variant.
 * A shaderpack guest whose program captures light links one program per depth test and depth write class, and warms
 * every class of each variant. {@link dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry#register(ShaderVariant)}
 * warms one before any material with it exists.
 */
public record ShaderVariant(MaterialShaders shaders, CutoutShader cutout, LightShader light, FogShader fog,
                            Transparency transparency, boolean colorWrite, boolean useLight) {
    /**
     * Variant identity by shader sources, under which registration and draw classification compare variants. Cutout
     * and fog precede the remaining tie-breakers: seeding order assigns the cutout/fog indices baked into generated
     * sources.
     */
    public static final Comparator<ShaderVariant> ORDER = Comparator
            .comparing((ShaderVariant v) -> v.shaders().vertexSource())
            .thenComparing(v -> v.shaders().fragmentSource()).thenComparing(v -> v.shaders().preservesPosition())
            .thenComparing(v -> v.cutout().source()).thenComparing(v -> v.light().source())
            .thenComparing(v -> v.fog().source()).thenComparing(ShaderVariant::transparency)
            .thenComparing(ShaderVariant::colorWrite).thenComparing(ShaderVariant::useLight);

    public static ShaderVariant of(Material material) {
        return new ShaderVariant(material.shaders(), material.cutout(), material.light(), material.fog(),
                material.transparency(), material.writeMask().color(), material.useLight());
    }
}
