package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.internal.DependencyInjection;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public interface BakedModelBufferer {
    BakedModelBufferer INSTANCE = DependencyInjection.load(BakedModelBufferer.class,
            "dev.engine_room.flywheel.lib.model.baked.BakedModelBuffererImpl");

    /**
     * Bakes a block state's model into immutable meshes, one per non-empty {@link ChunkSectionLayer}.
     */
    EnumMap<ChunkSectionLayer, BakedMesh> bufferBlock(BlockState state, int cullMask, long seed);

    /**
     * Bakes a standalone {@link BlockStateModel} into immutable meshes, one per non-empty layer.
     */
    EnumMap<ChunkSectionLayer, BakedMesh> bufferModel(BlockStateModel model, @Nullable PoseStack poseStack);

    /**
     * Bakes vanilla's entity-context block draw ({@link DisplayBlockCapture}) as its renderer writes it: tint only, no
     * face shade or AO, one mesh per block-model submit. {@code null} when a special renderer draws the block.
     */
    @Nullable
    List<DisplayMesh> bufferDisplayBlock(BlockState state, BlockDisplayContext context, boolean zOffset);

    record DisplayMesh(RenderType renderType, BakedMesh mesh) {
    }

    /**
     * Bakes an item stack's resolved geometry into immutable meshes in the item's display space.
     */
    @Nullable
    ItemMeshes bufferItem(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner, int seed);

    /**
     * {@link #bufferItem} in the frame {@code ItemStackVisual.beginFrame}'s pose places: layer 0's display transform
     * left to that pose. Every layer must share it.
     */
    @Nullable
    ItemMeshes bufferItemInVisualFrame(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner,
                                       int seed);

    enum ItemMeshKey {
        CUTOUT_ITEMS_ATLAS(ChunkSectionLayer.CUTOUT, false),
        CUTOUT_BLOCKS_ATLAS(ChunkSectionLayer.CUTOUT, true),
        TRANSLUCENT_ITEMS_ATLAS(ChunkSectionLayer.TRANSLUCENT, false),
        TRANSLUCENT_BLOCKS_ATLAS(ChunkSectionLayer.TRANSLUCENT, true);

        private final ChunkSectionLayer layer;
        private final boolean blocksAtlas;

        ItemMeshKey(ChunkSectionLayer layer, boolean blocksAtlas) {
            this.layer = layer;
            this.blocksAtlas = blocksAtlas;
        }

        public static ItemMeshKey of(boolean translucent, boolean blocksAtlas) {
            if (translucent) {
                return blocksAtlas ? TRANSLUCENT_BLOCKS_ATLAS : TRANSLUCENT_ITEMS_ATLAS;
            }
            return blocksAtlas ? CUTOUT_BLOCKS_ATLAS : CUTOUT_ITEMS_ATLAS;
        }

        public ChunkSectionLayer layer() {
            return layer;
        }

        public boolean blocksAtlas() {
            return blocksAtlas;
        }
    }

    /**
     * Result of {@link #bufferItem}: per-bucket meshes, glint flag, and the resolved model identity (the correct cache key).
     * {@code ownerDependent}: path reads holder state (use, pull, cooldown, main arm) => moves without a stack change.
     */
    record ItemMeshes(Map<ItemMeshKey, BakedMesh> meshes, boolean foil, float modelMinY, float modelZSize,
                      boolean stackDetermined, boolean ownerDependent, Object identity) {
    }
}
