package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.*;
import net.minecraft.util.ResourceLocation;

public final class Materials {
    private static final ResourceLocation ENCHANTED_GLINT_ITEM = new ResourceLocation("textures/misc/enchanted_item_glint.png");
    private static final ResourceLocation ENCHANTED_GLINT_ENTITY = ENCHANTED_GLINT_ITEM;

    public static final Material SOLID_BLOCK = SimpleMaterial.builder()
            .build();
    public static final Material SOLID_UNSHADED_BLOCK = SimpleMaterial.builderOf(SOLID_BLOCK)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .build();

    public static final Material CUTOUT_MIPPED_BLOCK = SimpleMaterial.builder()
            .cutout(CutoutShaders.HALF)
            .build();
    public static final Material CUTOUT_MIPPED_UNSHADED_BLOCK = SimpleMaterial.builderOf(CUTOUT_MIPPED_BLOCK)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .build();

    public static final Material CUTOUT_BLOCK = SimpleMaterial.builder()
            .cutout(CutoutShaders.ONE_TENTH)
            .mipmap(false)
            .build();
    public static final Material CUTOUT_UNSHADED_BLOCK = SimpleMaterial.builderOf(CUTOUT_BLOCK)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .build();

    public static final Material TRANSLUCENT_BLOCK = SimpleMaterial.builder()
            .transparency(Transparency.ORDER_INDEPENDENT)
            .build();
    public static final Material TRANSLUCENT_UNSHADED_BLOCK = SimpleMaterial.builderOf(TRANSLUCENT_BLOCK)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .build();

    public static final Material TRIPWIRE_BLOCK = SimpleMaterial.builder()
            .cutout(CutoutShaders.ONE_TENTH)
            .transparency(Transparency.ORDER_INDEPENDENT)
            .build();
    public static final Material TRIPWIRE_UNSHADED_BLOCK = SimpleMaterial.builderOf(TRIPWIRE_BLOCK)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .build();

    public static final Material CUTOUT = SimpleMaterial.builder()
            .cutout(CutoutShaders.HALF)
            // CHUNK mode matches 1.12.2 ambient-only GL_LIGHTING for entity renders (no
            // directional contribution), whereas ENTITY mode would be too dark on the sides.
            .cardinalLightingMode(CardinalLightingMode.CHUNK)
            .build();

    public static final Material CUTOUT_NO_CULL = SimpleMaterial.builderOf(CUTOUT)
            .backfaceCulling(false)
            .build();

    public static final Material CUTOUT_CLIP_SLAB = SimpleMaterial.builderOf(CUTOUT)
            .cutout(CutoutShaders.CLIP_SLAB)
            .build();

    public static final Material CUTOUT_CLIP_HALFSPACE = SimpleMaterial.builderOf(CUTOUT)
            .cutout(CutoutShaders.CLIP_HALFSPACE)
            .build();

    public static final Material TRANSLUCENT = SimpleMaterial.builder()
            .transparency(Transparency.ORDER_INDEPENDENT)
            .build();

    public static final Material TRANSLUCENT_NO_CULL = SimpleMaterial.builderOf(TRANSLUCENT)
            .backfaceCulling(false)
            .build();

    public static final Material TRANSLUCENT_NO_DEPTH_WRITE = SimpleMaterial.builderOf(TRANSLUCENT)
            .writeMask(WriteMask.COLOR)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useLight(false)
            .build();

    public static final Material TRANSLUCENT_NO_DEPTH_WRITE_NO_CULL = SimpleMaterial.builderOf(TRANSLUCENT_NO_DEPTH_WRITE)
            .backfaceCulling(false)
            .build();

    public static final Material ADDITIVE = SimpleMaterial.builder()
            .transparency(Transparency.ADDITIVE)
            .writeMask(WriteMask.COLOR)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useLight(false)
            .build();

    public static final Material ADDITIVE_NO_CULL = SimpleMaterial.builderOf(ADDITIVE)
            .backfaceCulling(false)
            .build();

    public static final Material CRUMBLING = SimpleMaterial.builder()
            .transparency(Transparency.CRUMBLING)
            .writeMask(WriteMask.COLOR)
            .build();

    // TNT minecart flash overlay (1.12.2 RenderTntMinecart parity).
    public static final Material TNT_FLASH_OVERLAY = SimpleMaterial.builder()
            .shaders(StandardMaterialShaders.TNT_FLASH)
            .transparency(Transparency.LIGHTNING)
            .writeMask(WriteMask.COLOR)
            .depthTest(DepthTest.EQUAL)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useLight(false)
            .useOverlay(false)
            .build();

    public static final Material GLINT = SimpleMaterial.builder()
            .texture(ENCHANTED_GLINT_ITEM)
            .shaders(StandardMaterialShaders.GLINT)
            .transparency(Transparency.GLINT)
            .writeMask(WriteMask.COLOR)
            .depthTest(DepthTest.EQUAL)
            // Backface culling stays ON (vs upstream Flywheel's `false`). 1.12.2 item models
            // are flat sprites with front+back quads at the same depth; with no culling and
            // depthTest=EQUAL both faces pass and the `src² + dst` glint blend gets applied
            // twice. Vanilla 1.12.2 RenderItem.renderEffect inherits the surrounding cull
            // state (enabled), so only the camera-facing face draws glint.
            .blur(true)
            .mipmap(false)
            // GLINT blend squares the source; dimming via lightmap or overlay makes glint
            // invisible outside max light. Matches RenderItem.renderEffect which disables lighting.
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useLight(false)
            .useOverlay(false)
            .build();

    public static final Material GLINT_ENTITY = SimpleMaterial.builderOf(GLINT)
            .texture(ENCHANTED_GLINT_ENTITY)
            .build();

    // Second glint pass (rotation -50°, period 3000ms). Vanilla 1.12.2 layered two passes with
    // different rotations; upstream Flywheel ships only one.
    public static final Material GLINT_2 = SimpleMaterial.builderOf(GLINT)
            .shaders(StandardMaterialShaders.GLINT_2)
            .build();

    public static final Material TRANSLUCENT_ENTITY = SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .cutout(CutoutShaders.ONE_TENTH)
            .mipmap(false)
            .build();

    private Materials() {
    }
}
