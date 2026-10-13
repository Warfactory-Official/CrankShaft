package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.util.ItemFoil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class InstancedArmorLayer {
    private static final Material GLINT_ARMOR = SimpleMaterial.builderOf(Materials.GLINT)
                                                      .texture(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR)
                                                      .shaders(StandardMaterialShaders.GLINT_ARMOR)
                                                      .build();
    #if MC >= 26.3
    private static final Material GLINT_TRIMMED_ARMOR = SimpleMaterial.builderOf(GLINT_ARMOR)
                                                              .polygonOffset(true)
                                                              .build();
    #endif
    #if MC < 26.3
    // 26.2: trim coplanar over armor -> polygon offset (vanilla VIEW_OFFSET_Z_LAYERING); palette sprite carries color.
    private static final Material TRIM_MATERIAL = SimpleMaterial.builder()
                                                        .texture(Sheets.ARMOR_TRIMS_SHEET)
                                                        .cutout(CutoutShaders.ONE_TENTH)
                                                        .backfaceCulling(false)
                                                        .polygonOffset(true)
                                                        .build();
    #endif
    private static final Function<Identifier, Material> BASE_MATERIAL =
            #if MC >= 26.3
            LivingEntityVisual::equipmentMaterial;
            #else
            LivingEntityVisual::materialFor;
            #endif

    private final EntityType<?> wearer;
    private final InstancerProvider provider;
    private final EquipmentAssetManager assets;
    private final Map<String, Integer> boneIndex;
    // The body's rest poses: armor bones pose at rest_armor + (captured - rest_body) -- vanilla's ARMOR setupAnim
    // writes angles absolute but leaves pivots at the armor bake's rest.
    private final float[] bodyRest;
    private final Slot[] slots;
    private boolean hidden;

    InstancedArmorLayer(InstancerProvider provider, EquipmentAssetManager assets,
                        ArmorModelSet<ModelLayerLocation> layers, boolean baby, Map<String, Integer> boneIndex,
                        float[] bodyRest, EntityType<?> wearer) {
        this.wearer = wearer;
        this.provider = provider;
        this.assets = assets;
        this.boneIndex = boneIndex;
        this.bodyRest = bodyRest;
        // Adults: legs use the inner (leggings) model, every other slot the outer; babies use HUMANOID_BABY for ALL slots (their own 64x64 texture layout); vanilla excludes armor stands from the baby branch, so a small stand's config stays adult.
        this.slots = new Slot[]{
                new Slot(EquipmentSlot.HEAD, layers.head(),
                        baby ? EquipmentClientInfo.LayerType.HUMANOID_BABY : EquipmentClientInfo.LayerType.HUMANOID),
                new Slot(EquipmentSlot.CHEST, layers.chest(),
                        baby ? EquipmentClientInfo.LayerType.HUMANOID_BABY : EquipmentClientInfo.LayerType.HUMANOID),
                new Slot(EquipmentSlot.LEGS, layers.legs(),
                        baby ? EquipmentClientInfo.LayerType.HUMANOID_BABY : EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS),
                new Slot(EquipmentSlot.FEET, layers.feet(),
                        baby ? EquipmentClientInfo.LayerType.HUMANOID_BABY : EquipmentClientInfo.LayerType.HUMANOID),
        };
    }

    private static int colorForLayer(EquipmentClientInfo.Layer layer, int dyeColor) {
        var dyeable = layer.dyeable();
        if (dyeable.isPresent()) {
            int colorWhenUndyed = dyeable.get()
                                         .colorWhenUndyed()
                                         .map(ARGB::opaque)
                                         .orElse(0);
            return dyeColor != 0 ? dyeColor : colorWhenUndyed;
        }
        return -1;
    }

    // Compat with Iris: equipment layers and their glint report the item, trims trim_<material>.
    private static ModelTree forItem(ModelTree tree, Identifier item) {
        return PackTaggedModel.tag(tree, List.of(PackIdentity.ofIrisItem(item)));
    }

    private static Identifier itemId(ItemStack stack) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        return model != null ? model : BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static Identifier trimId(ArmorTrim trim) {
        #if MC >= 26.3
        String palettePath = trim.material().value().paletteId().getPath();
        return Identifier.withDefaultNamespace("trim_" + palettePath.substring(palettePath.lastIndexOf('/') + 1));
        #else
        return Identifier.withDefaultNamespace("trim_" + trim.material()
                                                             .value()
                                                             .assets()
                                                             .base()
                                                             .suffix());
        #endif
    }

    private static void flattenNamed(InstanceTree node, String name, List<InstanceTree> nodes, List<String> names) {
        nodes.add(node);
        names.add(name);
        for (int i = 0; i < node.childCount(); i++) {
            flattenNamed(node.child(i), node.childName(i), nodes, names);
        }
    }

    void apply(ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet, float[] transforms, Matrix4f root,
               int light) {
        if (hidden) {
            for (Slot slot : slots) {
                for (Draw draw : slot.draws) {
                    draw.tree.visible(true);
                }
            }
            hidden = false;
        }
        applySlot(slots[0], head, transforms, root, light);
        applySlot(slots[1], chest, transforms, root, light);
        applySlot(slots[2], legs, transforms, root, light);
        applySlot(slots[3], feet, transforms, root, light);
    }

    void hide() {
        if (hidden) {
            return;
        }
        for (Slot slot : slots) {
            for (Draw draw : slot.draws) {
                draw.tree.visible(false);
            }
        }
        hidden = true;
    }

    void delete() {
        for (Slot slot : slots) {
            for (Draw draw : slot.draws) {
                draw.tree.delete();
            }
            slot.draws = List.of();
        }
    }

    private void applySlot(Slot slot, ItemStack stack, float[] transforms, Matrix4f root, int light) {
        if (!ItemStack.matches(slot.stack, stack)) {
            if (rebuild(slot, stack)) {
                slot.stack = stack;
            }
        }
        for (Draw draw : slot.draws) {
            pose(draw, slot.rest, transforms, root, light);
        }
    }

    private boolean rebuild(Slot slot, ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        List<Draw> draws = new ArrayList<>();
        if (!stack.isEmpty() && equippable != null && equippable.assetId().isPresent()
                && equippable.slot() == slot.slot
                && !equipmentDraws(assets, equippable.assetId().get(), slot.layerType, slot.layer, stack, wearer,
                        BASE_MATERIAL, (tree, color, drawBias) -> addDraw(draws, tree, color, drawBias))) {
            return false;
        }
        for (Draw draw : slot.draws) {
            draw.tree.delete();
        }
        slot.draws = draws;
        return true;
    }

    /** Vanilla equipment layer order. {@code false} => 26.3 trim palette pending; nothing emitted. */
    static boolean equipmentDraws(EquipmentAssetManager assets, ResourceKey<EquipmentAsset> assetId,
                                  EquipmentClientInfo.LayerType layerType, ModelLayerLocation layer, ItemStack source,
                                  EntityType<?> wearer, Function<Identifier, Material> baseMaterial, DrawSink sink) {
        EquipmentClientInfo info = assets.get(assetId);
        List<EquipmentClientInfo.Layer> infoLayers = info.getLayers(layerType);
        if (infoLayers.isEmpty()) {
            return true;
        }
        ArmorTrim trim = layerType == EquipmentClientInfo.LayerType.HUMANOID_BABY ? null
                : source.get(DataComponents.TRIM);
        #if MC >= 26.3
        ArmorTrimTextures.Prepared preparedTrim = null;
        if (trim != null) {
            preparedTrim = ArmorTrimTextures.getIfReady(trim, layerType, info, true);
            if (preparedTrim == null) {
                return false;
            }
        }
        #endif
        int dyeColor = DyedItemColor.getOrDefault(source, 0);
        Identifier item = itemId(source);
        boolean foil = ItemFoil.of(source);
        boolean anyBase = false;
        #if MC >= 26.3
        int baseCount = visibleLayerCount(infoLayers, dyeColor);
        int baseIndex = 0;
        #endif
        for (EquipmentClientInfo.Layer infoLayer : infoLayers) {
            int color = colorForLayer(infoLayer, dyeColor);
            if (color == 0) {
                continue;
            }
            Identifier texture = infoLayer.getTextureLocation(layerType);
            EntityFeatureCompat.observeTexture(wearer, texture);
            ModelTree base = forItem(ModelTrees.of(layer, baseMaterial.apply(texture)), item);
            #if MC >= 26.3
            // 26.3: native equipment base, trim and glint share the same view-offset depth.
            sink.add(base, color, baseDrawBias(baseIndex++, baseCount, foil && trim == null));
            #else
            sink.add(base, color, 0);
            #endif
            anyBase = true;
        }
        #if MC >= 26.3
        if (trim != null) {
            sink.add(forItem(ModelTrees.of(layer, preparedTrim.mapping(), preparedTrim.material()), trimId(trim)), -1,
                    1);
        }
        if (foil && (anyBase || trim != null)) {
            sink.add(forItem(ModelTrees.of(layer, GLINT_TRIMMED_ARMOR), item), -1,
                    glintDrawBias(baseCount, trim != null));
        }
        #else
        if (foil && anyBase) {
            sink.add(forItem(ModelTrees.of(layer, GLINT_ARMOR), item), -1, 0);
        }
        if (trim != null) {
            TextureAtlasSprite sprite = Minecraft.getInstance()
                                                 .getAtlasManager()
                                                 .getAtlasOrThrow(AtlasIds.ARMOR_TRIMS)
                                                 .getSprite(trim.layerAssetId(layerType.trimAssetPrefix(), assetId));
            sink.add(forItem(ModelTrees.of(layer, sprite, TRIM_MATERIAL), trimId(trim)), -1, 0);
        }
        #endif
        return true;
    }

    private void addDraw(List<Draw> draws, ModelTree modelTree, int color, int drawBias) {
        InstanceTree tree =
                InstanceTree.create(drawBias == 0 ? provider : orderedProvider(provider, drawBias), modelTree);
        List<InstanceTree> nodeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        flattenNamed(tree, "", nodeList, nameList);
        int[] map = new int[nodeList.size()];
        for (int i = 0; i < map.length; i++) {
            map[i] = boneIndex.getOrDefault(nameList.get(i), -1);
        }
        draws.add(new Draw(tree, nodeList.toArray(new InstanceTree[0]), map, color));
    }

    // Used only while constructing a tree: decals consume base depth, then glint overlays the decal.
    static InstancerProvider orderedProvider(InstancerProvider provider, int drawBias) {
        return new InstancerProvider() {
            @Override
            public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int ignored) {
                return provider.instancer(type, model, drawBias);
            }
        };
    }

    #if MC >= 26.3
    private static int visibleLayerCount(List<EquipmentClientInfo.Layer> layers, int dyeColor) {
        int count = 0;
        for (EquipmentClientInfo.Layer layer : layers) {
            if (colorForLayer(layer, dyeColor) != 0) count++;
        }
        return count;
    }

    private static int baseDrawBias(int index, int count, boolean untrimmedFoil) {
        if (count <= 1) return 0;
        int bias = index - count + 1;
        // 26.3: untrimmed native glint belongs to the first base, before subsequent base overlays.
        return untrimmedFoil && index == 0 ? bias - 1 : bias;
    }

    private static int glintDrawBias(int baseCount, boolean hasTrim) {
        return !hasTrim && baseCount > 1 ? 1 - baseCount : 2;
    }
    #endif

    interface DrawSink {
        void add(ModelTree tree, int color, int drawBias);
    }

    private void pose(Draw draw, float[] rest, float[] transforms, Matrix4f root, int light) {
        InstanceTree[] nodes = draw.nodes;
        int[] map = draw.nodeToBody;
        for (int i = 0; i < nodes.length; i++) {
            int bi = map[i];
            if (bi < 0) {
                continue;
            }
            InstanceTree node = nodes[i];
            int b = bi * 9;
            int r = i * 9;
            node.pos(rest[r] + transforms[b] - bodyRest[b],
                    rest[r + 1] + transforms[b + 1] - bodyRest[b + 1],
                    rest[r + 2] + transforms[b + 2] - bodyRest[b + 2]);
            node.rotation(transforms[b + 3], transforms[b + 4], transforms[b + 5]);
            node.scale(transforms[b + 6], transforms[b + 7], transforms[b + 8]);
        }
        draw.tree.updateInstances(root);
        int color = draw.color;
        draw.tree.traverse(instance -> {
            instance.light(light);
            instance.overlay(OverlayTexture.NO_OVERLAY);
            instance.colorArgb(color);
            instance.setChanged();
        });
    }

    private static final class Slot {
        final EquipmentSlot slot;
        final ModelLayerLocation layer;
        final EquipmentClientInfo.LayerType layerType;
        final float[] rest;
        ItemStack stack = ItemStack.EMPTY;
        List<Draw> draws = List.of();

        Slot(EquipmentSlot slot, ModelLayerLocation layer, EquipmentClientInfo.LayerType layerType) {
            this.slot = slot;
            this.layer = layer;
            this.layerType = layerType;
            this.rest = LivingEntityVisual.layerRestPoses(layer);
        }
    }

    private record Draw(InstanceTree tree, InstanceTree[] nodes, int[] nodeToBody, int color) {
    }
}
