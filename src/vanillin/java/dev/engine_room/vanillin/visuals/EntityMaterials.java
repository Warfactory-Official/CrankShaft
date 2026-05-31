package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.BlockModels;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

public final class EntityMaterials {
    // Block models attached to entities (enderman held block, golem poppy, mooshroom mushrooms).
    // CUTOUT_UNSHADED_BLOCK carries no texture (BlockModels wraps it with the atlas at use-time), so bind
    // the block atlas here; no-cull replaces vanilla's single-face cull of the flipped block models.
    public static final Material BLOCK_OVERLAY = SimpleMaterial.builderOf(Materials.CUTOUT_UNSHADED_BLOCK)
            .texture(TextureMap.LOCATION_BLOCKS_TEXTURE)
            .backfaceCulling(false)
            .build();
    public static final RendererReloadCache<IBlockState, Model> BLOCK_OVERLAY_MODELS =
            new RendererReloadCache<>(state -> BlockModels.get(state, (layer, shaded) -> BLOCK_OVERLAY));

    private EntityMaterials() {
    }

    public static Material living(String texture) {
        return SimpleMaterial.builderOf(Materials.CUTOUT_NO_CULL)
                .cardinalLightingMode(CardinalLightingMode.ENTITY)
                .texture(new ResourceLocation(texture))
                .mipmap(false)
                .build();
    }

    public static Material emissive(String texture) {
        return SimpleMaterial.builderOf(Materials.ADDITIVE_NO_CULL)
                .texture(new ResourceLocation(texture))
                .mipmap(false)
                .build();
    }
}
