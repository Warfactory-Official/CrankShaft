package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * The captured vanilla translucent rendertype families with a matching OIT producer variant. Each family
 * mirrors ONE vanilla pipeline's coloring recipe + fixed-function state; {@link BerTranslucentCapture#familyOf}
 * routes a captured draw to its family, and every producer cache (GL + VK, wavelet + insert) keys on
 * (family, mode). The flags below drive the shared bind/binding-layout code -- the per-family shader recipe
 * lives in the {@code ber_oit}/{@code flw_ber_oit} sources ({@code RenderPassShaders} applies the defines).
 */
public enum BerFamily {
    /**
     * Vanilla {@code ENTITY_TRANSLUCENT}: PER_FACE_LIGHTING diffuse + lightmap + overlay, double-sided.
     */
    ENTITY("", DefaultVertexFormat.ENTITY, false, true, true, true),
    /**
     * Vanilla {@code ITEM_TRANSLUCENT} (dropped items, item frames, displays, held/carried translucent) AND
     * vanilla {@code ENTITY_TRANSLUCENT_CULL} (experience orbs, invisible-body ghost silhouettes) -- the two
     * pipelines' coloring is identical (core/item ~ core/entity's plain branch): single
     * {@code minecraft_mix_light} diffuse + lightmap + overlay, back-face culled.
     */
    ITEM("_item", DefaultVertexFormat.ENTITY, true, true, true, true),
    /**
     * Vanilla {@code ENTITY_TRANSLUCENT_EMISSIVE} (Warden glow layers, Breeze eyes): PER_FACE_LIGHTING diffuse
     * + overlay, NO lightmap (the EMISSIVE define compiles the sample + multiply out entirely), double-sided.
     */
    ENTITY_EMISSIVE("_emissive", DefaultVertexFormat.ENTITY, false, true, false, true),
    /**
     * Vanilla {@code TRANSLUCENT_BLOCK} (falling blocks + piston-carried blocks with a translucent layer):
     * BLOCK format, lightmap folded into vertexColor in the vertex, no overlay/no diffuse, back-face culled.
     * The 0.1 cutout tests the MODULATED alpha (unlike the entity families' raw-atlas-alpha test).
     */
    MOVING_BLOCK("_block", DefaultVertexFormat.BLOCK, true, false, true, false),
    /**
     * Vanilla {@code BEACON_BEAM_TRANSLUCENT} (the beacon/end-gateway beam's glow ring): BLOCK format but only
     * Position/Color/UV0 are consumed -- texture x vertexColor x ColorModulator, no cutout, no lightmap/overlay/
     * diffuse, fog from {@code 1.0/gl_FragCoord.w}, back-face culled.
     */
    BEAM("_beam", DefaultVertexFormat.BLOCK, true, false, false, false);

    public static final BerFamily[] VALUES = values();
    /**
     * Codegen/pipeline id suffix ({@code ""} keeps the ENTITY ids).
     */
    public final String suffix;
    /**
     * The captured geometry's vertex format (pipeline binding 0).
     */
    public final VertexFormat format;
    /**
     * Vanilla's cull state for this family (the OIT producer mirrors it).
     */
    public final boolean cull;
    /**
     * Sampler1 (overlay) texelFetched in the vertex stage.
     */
    public final boolean overlay;
    /**
     * Sampler2 (lightmap) sampled in the vertex stage.
     */
    public final boolean lightmap;
    /**
     * The Lighting UBO (Light0/1_Direction) consumed by the vertex diffuse.
     */
    public final boolean lighting;

    BerFamily(String suffix, VertexFormat format, boolean cull, boolean overlay, boolean lightmap, boolean lighting) {
        this.suffix = suffix;
        this.format = format;
        this.cull = cull;
        this.overlay = overlay;
        this.lightmap = lightmap;
        this.lighting = lighting;
    }
}
