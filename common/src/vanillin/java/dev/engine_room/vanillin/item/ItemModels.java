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
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
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
 * cached on the resolved model identity so identical geometry shares a model -- and an instancer. The key also holds
 * the item and {@code item_model}: baked meshes record them (shaderpack item ids).
 */
public final class ItemModels {
    public static final Model EMPTY = new SimpleModel(List.of());
    public static final Baked EMPTY_BAKED = new Baked(EMPTY, 0.0f, 0.0f, null);

    // Port: bakes capture their stack, so each map rides one RendererReloadCache entry and drops with its reloads.
    private static final RendererReloadCache<Boolean, Map<ModelKey, Baked>> MODEL_CACHE = new RendererReloadCache<>(
            $ -> new ConcurrentHashMap<>());
    private static final RendererReloadCache<Boolean, Map<SupportKey, Boolean>> SUPPORT_CACHE =
            new RendererReloadCache<>($ -> new ConcurrentHashMap<>());

    private ItemModels() {
    }

    public static boolean isSupported(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner,
                                      int seed) {
        if (stack.isEmpty()) {
            return false;
        }
        return SUPPORT_CACHE.get(true).computeIfAbsent(
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
            SUPPORT_CACHE.get(true).put(new SupportKey(stack.getItem(), stack.get(DataComponents.ITEM_MODEL), displayContext),
                    false);
            return EMPTY_BAKED;
        }
        return MODEL_CACHE.get(true).computeIfAbsent(new ModelKey(displayContext, result.identity(), stack.getItem(),
                stack.get(DataComponents.ITEM_MODEL), result.foil()), $ -> buildModel(result));
    }

    // TODO: revisit -- rebake was consolidated onto ItemModels from a per-visual helper; reconsider
    // whether the delete/create instance lifecycle belongs here, and the empty-stack bake() call it now always makes.
    @Nullable
    public static TransformedInstance rebake(InstancerProvider instancerProvider, @Nullable TransformedInstance current,
                                             ItemStack stack, ItemDisplayContext displayContext,
                                             @Nullable ItemOwner owner, int seed) {
        return rebake(instancerProvider, current, bake(stack, displayContext, owner, seed));
    }

    @Nullable
    public static TransformedInstance rebake(InstancerProvider instancerProvider, @Nullable TransformedInstance current,
                                             Baked baked) {
        if (current != null) {
            current.delete();
        }
        Model model = baked.model();
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
        return new Baked(new SimpleModel(configured), result.modelMinY(), result.modelZSize(),
                result.ownerDependent() ? result.identity() : null);
    }

    /**
     * Whether the holder's state moved {@code baked}'s resolution to another model (bow, crossbow pull).
     *
     * @param baked a bake with an {@link Baked#ownerIdentity}.
     * @param stack the holder's own instance, as the bake's: use properties test {@code getUseItem() == stack}.
     */
    public static boolean moved(Baked baked, Resolution scratch, ItemStack stack, ItemDisplayContext displayContext,
                                LivingEntity holder, int seed) {
        scratch.identity.clear();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getItemModelResolver()
                 .updateForTopItem(scratch, stack, displayContext, minecraft.level, holder, seed);
        return !baked.ownerIdentity().equals(scratch.identity);
    }

    /**
     * @param ownerIdentity the resolved identity when it reads the holder's state, else {@code null}.
     */
    public record Baked(Model model, float modelMinY, float modelZSize, @Nullable Object ownerIdentity) {
    }

    /**
     * {@link #moved} scratch; one thread at a time.
     */
    public static final class Resolution extends ItemStackRenderState {
        private final List<Object> identity = new ArrayList<>();

        @Override
        public void appendModelIdentityElement(Object element) {
            identity.add(element);
        }
    }

    // foil: an identity resolved during Iris's shadow pass lacks vanilla's foil element (ItemFoil).
    private record ModelKey(ItemDisplayContext displayContext, Object identity, Item item,
                            @Nullable Identifier itemModel, boolean foil) {
    }

    private record SupportKey(Item item, @Nullable Identifier modelId, ItemDisplayContext displayContext) {
    }
}
