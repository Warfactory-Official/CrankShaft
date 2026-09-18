package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.DrawTags;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.lib.model.ItemTaggedModel;
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
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

final class GuestDrawTags {
    private static final NamespacedId FLAME = new NamespacedId("minecraft", "entity_flame");

    private GuestDrawTags() {
    }

    static DrawTags of(Environment environment, Model model, Material material, Mesh mesh) {
        int drawTag = environment.drawTag();
        Object2IntFunction<NamespacedId> entityIds = WorldRenderingSettings.INSTANCE.getEntityIds();
        if (material == FireComponent.FIRE_MATERIAL && entityIds != null) {
            drawTag = TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, entityIds.applyAsInt(FLAME));
        } else if (GuestEntityShadows.isBlobShadow(material)) {
            // Iris captures no id for deferred shadow submits: all ids 0.
            drawTag = TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, 0);
        }
        Item item = model instanceof ItemTaggedModel tagged ? tagged.item()
                : mesh instanceof BakedMesh baked ? baked.item() : null;
        return new DrawTags(drawTag, item == null ? 0
                : itemTag(item, mesh instanceof BakedMesh baked ? baked.itemModel() : null));
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
