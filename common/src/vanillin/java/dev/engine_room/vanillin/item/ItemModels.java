package dev.engine_room.vanillin.item;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.model.Model.ConfiguredMesh;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBufferer;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBufferer.ItemMeshes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bakes an {@link ItemStack} into a flywheel {@link Model} via the 26.2 {@code ItemStackRenderState} extraction,
 * cached on the resolved model identity so identical geometry shares a model -- and an instancer.
 */
public final class ItemModels {
    public static final Model EMPTY = new SimpleModel(List.of());
    private static final Baked EMPTY_BAKED = new Baked(EMPTY, 0.0f, 0.0f);

    private static final Map<ModelKey, Baked> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<SupportKey, Boolean> SUPPORT_CACHE = new ConcurrentHashMap<>();

    private ItemModels() {
    }

    public static boolean isSupported(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner,
                                      int seed) {
        if (stack.isEmpty()) {
            return false;
        }
        return SUPPORT_CACHE.computeIfAbsent(
                new SupportKey(stack.getItem(), stack.get(DataComponents.ITEM_MODEL), displayContext), $ -> {
                    ItemMeshes result = BakedModelBufferer.INSTANCE.bufferItem(stack, displayContext, owner, seed);
                    return result != null && result.stackDetermined();
                });
    }

    public static Model get(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner, int seed) {
        return bake(stack, displayContext, owner, seed).model();
    }

    public static Baked bake(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner, int seed) {
        if (stack.isEmpty()) {
            return EMPTY_BAKED;
        }
        ItemMeshes result = BakedModelBufferer.INSTANCE.bufferItem(stack, displayContext, owner, seed);
        if (result == null || !result.stackDetermined()) {
            // Special-renderer/time-varying resolutions render via vanilla (the visual must draw nothing on top). Also DEMOTE the gate verdict: the support key is coarser than the resolution (e.g. custom_model_data selecting a special-renderer branch), so a sibling stack may have seeded TRUE -- the whole key goes vanilla and the visuals re-check the gate per frame.
            SUPPORT_CACHE.put(new SupportKey(stack.getItem(), stack.get(DataComponents.ITEM_MODEL), displayContext),
                    false);
            return EMPTY_BAKED;
        }
        return MODEL_CACHE.computeIfAbsent(new ModelKey(displayContext, result.identity()), $ -> buildModel(result));
    }

    // TODO: revisit -- rebake was consolidated onto ItemModels from a per-visual helper; reconsider
    // whether the delete/create instance lifecycle belongs here, and the empty-stack bake() call it now always makes.
    @Nullable
    public static TransformedInstance rebake(InstancerProvider instancerProvider, @Nullable TransformedInstance current,
                                             ItemStack stack, ItemDisplayContext displayContext,
                                             @Nullable ItemOwner owner, int seed) {
        if (current != null) {
            current.delete();
        }
        Model model = bake(stack, displayContext, owner, seed).model();
        if (model.meshes().isEmpty()) {
            return null;
        }
        return instancerProvider.instancer(InstanceTypes.TRANSFORMED, model)
                                .createInstance();
    }

    private static Baked buildModel(ItemMeshes result) {
        List<ConfiguredMesh> configured = new ArrayList<>();
        for (var entry : result.meshes().entrySet()) {
            Material material = ModelUtil.getItemMaterial(entry.getKey().layer(), entry.getKey().blocksAtlas());
            if (material == null) {
                continue;
            }
            configured.add(new ConfiguredMesh(material, entry.getValue()));
            if (result.foil()) {
                configured.add(new ConfiguredMesh(Materials.GLINT, entry.getValue()));
            }
        }
        if (configured.isEmpty()) {
            return EMPTY_BAKED;
        }
        return new Baked(new SimpleModel(configured), result.modelMinY(), result.modelZSize());
    }

    public static void clear() {
        MODEL_CACHE.clear();
        SUPPORT_CACHE.clear();
    }

    public record Baked(Model model, float modelMinY, float modelZSize) {
    }

    private record ModelKey(ItemDisplayContext displayContext, Object identity) {
    }

    private record SupportKey(Item item, @Nullable Identifier modelId, ItemDisplayContext displayContext) {
    }
}
