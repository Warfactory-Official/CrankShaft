package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
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
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class InstancedArmorLayer {
    static final Material GLINT_ARMOR = SimpleMaterial.builderOf(Materials.GLINT)
                                                      .texture(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR)
                                                      .shaders(StandardMaterialShaders.GLINT_ARMOR)
                                                      .build();
    // Armor trim: the armor model re-drawn against the armor_trims atlas with its UVs remapped into the trim's sprite sub-rect (RetexturedMesh); coplanar over the armor -> polygon-offset (vanilla's VIEW_OFFSET_Z_LAYERING); untinted -- the palette sprite carries the colours.
    static final Material TRIM_MATERIAL = SimpleMaterial.builder()
                                                        .texture(Sheets.ARMOR_TRIMS_SHEET)
                                                        .cutout(CutoutShaders.ONE_TENTH)
                                                        .backfaceCulling(false)
                                                        .polygonOffset(true)
                                                        .build();

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
                        float[] bodyRest) {
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

    static int colorForLayer(EquipmentClientInfo.Layer layer, int dyeColor) {
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
            rebuild(slot, stack);
            slot.stack = stack;
        }
        for (Draw draw : slot.draws) {
            pose(draw, slot.rest, transforms, root, light);
        }
    }

    private void rebuild(Slot slot, ItemStack stack) {
        for (Draw draw : slot.draws) {
            draw.tree.delete();
        }
        slot.draws = List.of();
        if (stack.isEmpty()) {
            return;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.assetId().isEmpty() || equippable.slot() != slot.slot) {
            return;
        }
        List<EquipmentClientInfo.Layer> infoLayers = assets.get(equippable.assetId().get())
                                                           .getLayers(slot.layerType);
        if (infoLayers.isEmpty()) {
            return;
        }
        int dyeColor = DyedItemColor.getOrDefault(stack, 0);
        List<Draw> draws = new ArrayList<>(infoLayers.size());
        for (EquipmentClientInfo.Layer layer : infoLayers) {
            int color = colorForLayer(layer, dyeColor);
            if (color == 0) {
                continue;
            }
            Identifier texture = layer.getTextureLocation(slot.layerType);
            addDraw(draws, ModelTrees.of(slot.layer, LivingEntityVisual.materialFor(texture)), color);
        }
        if (stack.hasFoil() && !draws.isEmpty()) {
            addDraw(draws, ModelTrees.of(slot.layer, GLINT_ARMOR), -1);
        }
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim != null && slot.layerType != EquipmentClientInfo.LayerType.HUMANOID_BABY) {
            Identifier spriteId = trim.layerAssetId(slot.layerType.trimAssetPrefix(), equippable.assetId().get());
            TextureAtlasSprite sprite = Minecraft.getInstance()
                                                 .getAtlasManager()
                                                 .getAtlasOrThrow(AtlasIds.ARMOR_TRIMS)
                                                 .getSprite(spriteId);
            addDraw(draws, ModelTrees.of(slot.layer, sprite, TRIM_MATERIAL), -1);
        }
        slot.draws = draws;
    }

    private void addDraw(List<Draw> draws, ModelTree modelTree, int color) {
        InstanceTree tree = InstanceTree.create(provider, modelTree);
        List<InstanceTree> nodeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        flattenNamed(tree, "", nodeList, nameList);
        int[] map = new int[nodeList.size()];
        for (int i = 0; i < map.length; i++) {
            map[i] = boneIndex.getOrDefault(nameList.get(i), -1);
        }
        draws.add(new Draw(tree, nodeList.toArray(new InstanceTree[0]), map, color));
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
