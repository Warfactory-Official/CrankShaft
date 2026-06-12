package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.vanillin.visuals.LivingEntityVisual.OverlayKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class InstancedEquipmentLayer {
    private static final String LEFT_REIN = "left_saddle_line";
    private static final String RIGHT_REIN = "right_saddle_line";
    // The camel saddle's reins bone (CamelSaddleModel) is one part with a different name than the equine lines.
    private static final String CAMEL_REIN = "reins";
    private final InstancerProvider provider;
    private final EquipmentAssetManager assets;
    private final Map<String, Integer> boneIndex;
    private final ModelLayerLocation layer;
    private final EquipmentClientInfo.LayerType layerType;
    // The equipment layer's rest poses + the body's rest poses: mapped bones pose at rest_layer + (captured -
    // rest_body), mirroring InstancedArmorLayer.
    private final float[] rest;
    private final float[] bodyRest;
    @Nullable
    private final Function<ItemStack, Identifier> crackTexture;
    @Nullable
    private final ResourceKey<EquipmentAsset> fallbackAsset;
    private final LivingEntityVisual.RiddenPose @Nullable [] riddenPoses;
    @Nullable
    private final Matrix4fc rootOffset;
    private ItemStack stack = ItemStack.EMPTY;
    private boolean lastFallback;
    private List<Draw> draws = List.of();
    private boolean hidden;

    InstancedEquipmentLayer(InstancerProvider provider, EquipmentAssetManager assets,
                            LivingEntityVisual.BodyEquipment equipment, Map<String, Integer> boneIndex,
                            float[] bodyRest) {
        this.provider = provider;
        this.assets = assets;
        this.layer = equipment.modelLayer();
        this.layerType = equipment.layerType();
        this.boneIndex = boneIndex;
        this.rest = LivingEntityVisual.layerRestPoses(layer);
        this.bodyRest = bodyRest;
        this.crackTexture = equipment.crackTexture();
        this.fallbackAsset = equipment.fallbackAsset();
        this.riddenPoses = equipment.riddenPoses();
        this.rootOffset = equipment.rootOffset();
    }

    private static void finish(Draw draw, Matrix4f root, int light) {
        draw.tree.updateInstances(root);
        int color = draw.color;
        draw.tree.traverse(instance -> {
            instance.light(light);
            instance.overlay(OverlayTexture.NO_OVERLAY);
            instance.colorArgb(color);
            instance.setChanged();
        });
    }

    private static int colorForLayer(EquipmentClientInfo.Layer layer, int dyeColor) {
        return InstancedArmorLayer.colorForLayer(layer, dyeColor);
    }

    private static void flattenNamed(InstanceTree node, String name, List<InstanceTree> nodes, List<String> names) {
        nodes.add(node);
        names.add(name);
        for (int i = 0; i < node.childCount(); i++) {
            flattenNamed(node.child(i), node.childName(i), nodes, names);
        }
    }

    void apply(ItemStack item, boolean ridden, boolean fallbackActive, float @Nullable [] selfPose,
               float[] transforms, boolean[] bodyDraw, Matrix4f root, int light) {
        if (hidden) {
            for (Draw draw : draws) {
                draw.tree.visible(true);
            }
            hidden = false;
        }
        if (!ItemStack.matches(stack, item) || fallbackActive != lastFallback) {
            rebuild(item, fallbackActive);
            stack = item;
            lastFallback = fallbackActive;
        }
        if (rootOffset != null && !draws.isEmpty()) {
            root = new Matrix4f(root).mul(rootOffset);
        }
        for (Draw draw : draws) {
            pose(draw, ridden, selfPose, transforms, bodyDraw, root, light);
        }
    }

    void hide() {
        if (hidden) {
            return;
        }
        for (Draw draw : draws) {
            draw.tree.visible(false);
        }
        hidden = true;
    }

    void delete() {
        for (Draw draw : draws) {
            draw.tree.delete();
        }
        draws = List.of();
    }

    private void rebuild(ItemStack item, boolean fallbackActive) {
        for (Draw draw : draws) {
            draw.tree.delete();
        }
        draws = List.of();
        Equippable equippable = item.get(DataComponents.EQUIPPABLE);
        ResourceKey<EquipmentAsset> assetId;
        ItemStack source;
        if (equippable != null && equippable.assetId().isPresent()) {
            assetId = equippable.assetId().get();
            source = item;
        } else if (fallbackAsset != null && fallbackActive) {
            assetId = fallbackAsset;
            source = ItemStack.EMPTY;
        } else {
            return;
        }
        List<EquipmentClientInfo.Layer> infoLayers = assets.get(assetId).getLayers(layerType);
        if (infoLayers.isEmpty()) {
            return;
        }
        int dyeColor = DyedItemColor.getOrDefault(source, 0);
        List<Draw> list = new ArrayList<>(infoLayers.size());
        for (EquipmentClientInfo.Layer infoLayer : infoLayers) {
            int color = colorForLayer(infoLayer, dyeColor);
            if (color == 0) {
                continue;
            }
            Identifier texture = infoLayer.getTextureLocation(layerType);
            addDraw(list, ModelTrees.of(layer, LivingEntityVisual.equipmentMaterial(texture)), color);
        }
        if (source.hasFoil() && !list.isEmpty()) {
            addDraw(list, ModelTrees.of(layer, InstancedArmorLayer.GLINT_ARMOR), -1);
        }
        ArmorTrim trim = source.get(DataComponents.TRIM);
        if (trim != null) {
            Identifier spriteId = trim.layerAssetId(layerType.trimAssetPrefix(), assetId);
            TextureAtlasSprite sprite = Minecraft.getInstance()
                                                 .getAtlasManager()
                                                 .getAtlasOrThrow(AtlasIds.ARMOR_TRIMS)
                                                 .getSprite(spriteId);
            addDraw(list, ModelTrees.of(layer, sprite, InstancedArmorLayer.TRIM_MATERIAL), -1);
        }
        if (crackTexture != null) {
            Identifier crack = crackTexture.apply(item);
            if (crack != null) {
                addDraw(list,
                        ModelTrees.of(layer, LivingEntityVisual.dynamicOverlayMaterial(crack, OverlayKind.TRANSLUCENT)),
                        -1);
            }
        }
        draws = list;
    }

    private void addDraw(List<Draw> list, ModelTree modelTree, int color) {
        InstanceTree tree = InstanceTree.create(provider, modelTree);
        List<InstanceTree> nodeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        flattenNamed(tree, "", nodeList, nameList);
        int[] map = new int[nodeList.size()];
        List<Integer> reins = new ArrayList<>();
        for (int i = 0; i < map.length; i++) {
            String name = nameList.get(i);
            map[i] = boneIndex.getOrDefault(name, -1);
            if (LEFT_REIN.equals(name) || RIGHT_REIN.equals(name) || CAMEL_REIN.equals(name)) {
                reins.add(i);
            }
        }
        int[] reinNodes = new int[reins.size()];
        for (int i = 0; i < reinNodes.length; i++) {
            reinNodes[i] = reins.get(i);
        }
        int[] poseNodes = new int[riddenPoses == null ? 0 : riddenPoses.length];
        for (int k = 0; k < poseNodes.length; k++) {
            poseNodes[k] = nameList.indexOf(riddenPoses[k].bone());
        }
        list.add(new Draw(tree, nodeList.toArray(new InstanceTree[0]), map, color, reinNodes, poseNodes));
    }

    private void pose(Draw draw, boolean ridden, float @Nullable [] selfPose, float[] transforms, boolean[] bodyDraw,
                      Matrix4f root, int light) {
        InstanceTree[] nodes = draw.nodes;
        if (selfPose != null) {
            // Self-animated equipment (elytra): the capture snapshot the model's bones in sorted-DFS order -- the same order this draw's nodes flatten in, so pose by index.
            if (selfPose.length != nodes.length * 9) {
                throw new IllegalStateException("self-animated equipment model/bake tree mismatch: " + layer);
            }
            for (int i = 0; i < nodes.length; i++) {
                InstanceTree node = nodes[i];
                int b = i * 9;
                node.pos(selfPose[b], selfPose[b + 1], selfPose[b + 2]);
                node.rotation(selfPose[b + 3], selfPose[b + 4], selfPose[b + 5]);
                node.scale(selfPose[b + 6], selfPose[b + 7], selfPose[b + 8]);
            }
            finish(draw, root, light);
            return;
        }
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
            // The decor model shares the body's setupAnim visibility (llama pack-chests); mirror the body bone's captured draw flag.
            node.skipDraw(!bodyDraw[bi]);
        }
        if (riddenPoses != null) {
            int[] poseNodes = draw.riddenPoseNodes;
            for (int k = 0; k < poseNodes.length; k++) {
                int ni = poseNodes[k];
                if (ni < 0) {
                    continue;
                }
                var p = ridden ? riddenPoses[k].ridden() : riddenPoses[k].notRidden();
                InstanceTree node = nodes[ni];
                node.pos(p.x(), p.y(), p.z());
                node.rotation(p.xRot(), p.yRot(), p.zRot());
                node.scale(p.xScale(), p.yScale(), p.zScale());
            }
        }
        finish(draw, root, light);
        // Saddle reins show only while ridden -- the saddle models gate them in setupAnim, which we don't run, so gate here.
        for (int ri : draw.reinNodes) {
            nodes[ri].skipDraw(!ridden);
        }
    }

    private record Draw(InstanceTree tree, InstanceTree[] nodes, int[] nodeToBody, int color, int[] reinNodes,
                        int[] riddenPoseNodes) {
    }
}
