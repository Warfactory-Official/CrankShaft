package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.*;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

public final class Materials {
    // 26.2: blur on the *_BLOCK family selects vanilla's moving-block atlas sampler in MaterialSamplers;
    // the *_BLOCK_ITEM family stays non-blur, matching the item/entity passes' mipped atlas sampler.
    public static final Material SOLID_BLOCK = SimpleMaterial.builder()
                                                             .cardinalLightingMode(CardinalLightingMode.CHUNK)
                                                             .light(LightShaders.SMOOTH)
                                                             .blur(true)
                                                             .build();
    public static final Material SOLID_UNSHADED_BLOCK = SimpleMaterial.builderOf(SOLID_BLOCK)
                                                                      .cardinalLightingMode(CardinalLightingMode.OFF)
                                                                      .build();
    public static final Material CUTOUT_MIPPED_BLOCK = SimpleMaterial.builder()
                                                                     .cardinalLightingMode(CardinalLightingMode.CHUNK)
                                                                     .light(LightShaders.SMOOTH)
                                                                     .cutout(CutoutShaders.HALF)
                                                                     .blur(true)
                                                                     .build();
    public static final Material CUTOUT_MIPPED_UNSHADED_BLOCK = SimpleMaterial.builderOf(CUTOUT_MIPPED_BLOCK)
                                                                              .cardinalLightingMode(
                                                                                      CardinalLightingMode.OFF)
                                                                              .build();
    public static final Material CUTOUT_BLOCK = SimpleMaterial.builder()
                                                              .cardinalLightingMode(CardinalLightingMode.CHUNK)
                                                              .light(LightShaders.SMOOTH)
                                                              .cutout(CutoutShaders.ONE_TENTH)
                                                              .mipmap(false)
                                                              .build();
    public static final Material CUTOUT_UNSHADED_BLOCK = SimpleMaterial.builderOf(CUTOUT_BLOCK)
                                                                       .cardinalLightingMode(CardinalLightingMode.OFF)
                                                                       .build();
    public static final Material TRANSLUCENT_BLOCK = SimpleMaterial.builder()
                                                                   .cardinalLightingMode(CardinalLightingMode.CHUNK)
                                                                   .light(LightShaders.SMOOTH)
                                                                   .transparency(Transparency.ORDER_INDEPENDENT)
                                                                   .blur(true)
                                                                   .build();
    public static final Material TRANSLUCENT_UNSHADED_BLOCK = SimpleMaterial.builderOf(TRANSLUCENT_BLOCK)
                                                                            .cardinalLightingMode(
                                                                                    CardinalLightingMode.OFF)
                                                                            .build();
    // Item materials (dropped items, item frames, item displays): the item baker keeps raw quad colours,
    // so directional shading comes from the shader (ENTITY cardinal lighting, the SimpleMaterial default),
    // and light uses the packed instance light, not the block SMOOTH terrain LUT.
    public static final Material SOLID_ITEM = SimpleMaterial.builder()
                                                            .texture(TextureAtlas.LOCATION_ITEMS)
                                                            .build();
    public static final Material CUTOUT_ITEM = SimpleMaterial.builderOf(SOLID_ITEM)
                                                             .cutout(CutoutShaders.ONE_TENTH)
                                                             .build();
    public static final Material TRANSLUCENT_ITEM = SimpleMaterial.builderOf(SOLID_ITEM)
                                                                  .transparency(Transparency.ORDER_INDEPENDENT)
                                                                  .build();
    // mipmap(false): vanilla's item/entity render types bind the atlas with its own UNMIPPED NEAREST
    // sampler (the characteristic vanilla shimmer); only terrain/moving-block passes bind mipped samplers.
    public static final Material SOLID_BLOCK_ITEM = SimpleMaterial.builderOf(SOLID_ITEM)
                                                                  .texture(TextureAtlas.LOCATION_BLOCKS)
                                                                  .mipmap(false)
                                                                  .build();
    public static final Material CUTOUT_BLOCK_ITEM = SimpleMaterial.builderOf(CUTOUT_ITEM)
                                                                   .texture(TextureAtlas.LOCATION_BLOCKS)
                                                                   .mipmap(false)
                                                                   .build();
    public static final Material TRANSLUCENT_BLOCK_ITEM = SimpleMaterial.builderOf(TRANSLUCENT_ITEM)
                                                                        .texture(TextureAtlas.LOCATION_BLOCKS)
                                                                        .mipmap(false)
                                                                        .build();
    public static final Material TRIPWIRE_BLOCK = SimpleMaterial.builder()
                                                                .cardinalLightingMode(CardinalLightingMode.CHUNK)
                                                                .light(LightShaders.SMOOTH)
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
                                                                            .cardinalLightingMode(
                                                                                    CardinalLightingMode.OFF)
                                                                            .useLight(false)
                                                                            .build();
    public static final Material TRANSLUCENT_NO_DEPTH_WRITE_NO_CULL = SimpleMaterial.builderOf(
                                                                                            TRANSLUCENT_NO_DEPTH_WRITE)
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
    public static final Material TRANSLUCENT_ENTITY = SimpleMaterial.builder()
                                                                    .transparency(Transparency.TRANSLUCENT)
                                                                    .cutout(CutoutShaders.ONE_TENTH)
                                                                    .mipmap(false)
                                                                    .build();
    private static final Identifier ENCHANTED_GLINT_ITEM = ItemFeatureRenderer.ENCHANTED_GLINT_ITEM;
    public static final Material GLINT = SimpleMaterial.builder()
                                                       .texture(ENCHANTED_GLINT_ITEM)
                                                       .shaders(StandardMaterialShaders.GLINT)
                                                       .transparency(Transparency.GLINT)
                                                       .writeMask(WriteMask.COLOR)
                                                       .depthTest(DepthTest.EQUAL)
                                                       .backfaceCulling(false)
                                                       .blur(true)
                                                       .mipmap(false)
                                                       .build();
    private static final Identifier ENCHANTED_GLINT_ENTITY = ENCHANTED_GLINT_ITEM;
    public static final Material GLINT_ENTITY = SimpleMaterial.builderOf(GLINT)
                                                              .texture(ENCHANTED_GLINT_ENTITY)
                                                              .shaders(StandardMaterialShaders.GLINT_ENTITY)
                                                              .build();

    private Materials() {
    }
}
