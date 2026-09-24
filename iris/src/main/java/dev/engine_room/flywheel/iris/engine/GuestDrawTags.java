package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.DrawTags;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.lib.model.ItemTaggedModel;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;

final class GuestDrawTags {
    private static final NamespacedId FLAME = new NamespacedId("minecraft", "entity_flame");

    private GuestDrawTags() {
    }

    static DrawTags of(Environment environment, Model model, Material material, Mesh mesh) {
        int drawTag = environment.drawTag();
        int itemTag = 0;
        Item taggedItem = null;
        if (model instanceof ItemTaggedModel tagged) {
            taggedItem = tagged.item();
            model = tagged.delegate();
        }
        Object2IntFunction<NamespacedId> entityIds = WorldRenderingSettings.INSTANCE.getEntityIds();
        if (material == FireComponent.FIRE_MATERIAL && entityIds != null) {
            drawTag = TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, entityIds.applyAsInt(FLAME));
        } else if (GuestEntityShadows.isBlobShadow(material)) {
            // Iris captures no id for deferred shadow submits: all ids 0.
            drawTag = TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, 0);
        } else if (model instanceof PackTaggedModel tagged) {
            int borrowed = borrowIdentity(tagged.identities(), drawTag);
            if (borrowed != 0) {
                drawTag = borrowed;
            }
            itemTag = itemIdentity(tagged.identities());
        }
        if (itemTag == 0) {
            Item item = taggedItem != null ? taggedItem : mesh instanceof BakedMesh baked ? baked.item() : null;
            itemTag = item == null ? 0 : itemTag(item, mesh instanceof BakedMesh baked ? baked.itemModel() : null);
        }
        return new DrawTags(drawTag, itemTag);
    }


    /**
     * First identity the running pack actually maps, as a draw tag; 0 when it maps none, so the draw keeps its own.
     * A pack's unmapped entries resolve to id 0, which is the same "no special handling" the absent case wants.
     */
    private static int borrowIdentity(List<PackIdentity> identities, int drawTag) {
        for (PackIdentity identity : identities) {
            switch (identity) {
                case PackIdentity.OfBlock(Block block) -> {
                    Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
                    int id = ids == null ? 0 : ids.getOrDefault(block.defaultBlockState(), 0);
                    if (id != 0) return TaggedEnvironment.tag(TaggedEnvironment.KIND_BORROWED_BLOCK, id);
                }
                case PackIdentity.OfState(BlockState state) -> {
                    Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
                    int id = ids == null ? 0 : ids.getOrDefault(state, 0);
                    if (id != 0) return TaggedEnvironment.tag(TaggedEnvironment.KIND_BORROWED_BLOCK, id);
                }
                case PackIdentity.OfEntity(String irisName) -> {
                    Object2IntFunction<NamespacedId> ids = WorldRenderingSettings.INSTANCE.getEntityIds();
                    int id = ids == null ? 0 : ids.applyAsInt(new NamespacedId("minecraft", irisName));
                    if (id != 0) return TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, id);
                }
                case PackIdentity.OfProgram(String irisProgram) -> {
                    int kind = switch (irisProgram) {
                        case "spidereyes" -> TaggedEnvironment.KIND_ENTITY_EYES;
                        case "entities_translucent" -> TaggedEnvironment.KIND_ENTITY_TRANSLUCENT;
                        case "entities" -> TaggedEnvironment.KIND_ENTITY_BLENDED;
                        default -> throw new IllegalArgumentException("No guest route to Iris program " + irisProgram);
                    };
                    if (TaggedEnvironment.isEntity(drawTag)) {
                        return TaggedEnvironment.tag(kind, TaggedEnvironment.id(drawTag));
                    }
                }
                case PackIdentity.OfItem ignored -> {
                }
            }
        }
        return 0;
    }

    // Iris MixinEquipmentLayerRenderer: a plain id lookup, no block-item branch.
    private static int itemIdentity(List<PackIdentity> identities) {
        Object2IntFunction<NamespacedId> itemIds = WorldRenderingSettings.INSTANCE.getItemIds();
        if (itemIds == null) {
            return 0;
        }
        for (PackIdentity identity : identities) {
            if (identity instanceof PackIdentity.OfItem(Identifier id)) {
                return TaggedEnvironment.tag(TaggedEnvironment.KIND_ITEM,
                        itemIds.applyAsInt(new NamespacedId(id.getNamespace(), id.getPath())));
            }
        }
        return 0;
    }

    // Iris ItemStackStateLayerMixin.iris$setupId.
    private static int itemTag(Item item, @Nullable Identifier itemModel) {
        Object2IntFunction<NamespacedId> itemIds = WorldRenderingSettings.INSTANCE.getItemIds();
        if (itemIds == null) {
            return 0;
        }
        if (item instanceof BlockItem blockItem && !(item instanceof SolidBucketItem)) {
            Object2IntMap<BlockState> blockIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
            return blockIds == null ? 0 : TaggedEnvironment.tag(TaggedEnvironment.KIND_BLOCK_ITEM,
                    blockIds.getOrDefault(blockItem.getBlock()
                                                   .defaultBlockState(), 0));
        }
        Identifier location = itemModel != null ? itemModel : BuiltInRegistries.ITEM.getKey(item);
        return TaggedEnvironment.tag(TaggedEnvironment.KIND_ITEM,
                itemIds.applyAsInt(new NamespacedId(location.getNamespace(), location.getPath())));
    }
}
