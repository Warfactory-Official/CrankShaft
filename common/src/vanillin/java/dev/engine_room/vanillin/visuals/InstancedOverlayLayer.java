package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.vanillin.visuals.LivingEntityVisual.OverlayKind;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class InstancedOverlayLayer {
    // Stacked coplanar overlays need distinct depths (instanced draws can't see vanilla's submit order): overlay
    // i's bones inflate by 1 + i*0.1% about their own pivots -- a root-scale would shift geometry off-origin.
    private static final float INFLATE_STEP = 0.001F;

    private final InstancerProvider provider;
    private final Map<String, Integer> boneIndex;
    private final Draw[] draws;

    InstancedOverlayLayer(InstancerProvider provider, Map<String, Integer> boneIndex,
                          List<LivingEntityVisual.Overlay> overlays) {
        this.provider = provider;
        this.boneIndex = boneIndex;
        this.draws = new Draw[overlays.size()];
        for (int i = 0; i < overlays.size(); i++) {
            LivingEntityVisual.Overlay overlay = overlays.get(i);
            Draw draw = new Draw(overlay.layer(), overlay.emissive(), overlay.dynamicKind());
            if (overlay.textureResolver() == null) {
                build(draw, InstanceTree.create(provider, ModelTrees.of(overlay.layer(), overlay.material())));
            }
            draws[i] = draw;
        }
    }

    private static void setShown(Draw draw, boolean shown) {
        if (draw.tree != null && draw.shown != shown) {
            draw.tree.visible(shown);
            draw.shown = shown;
        }
    }

    private static void pose(Draw draw, float inflate, float[] transforms, Matrix4f root, int light, int overlayCoords,
                             int color) {
        InstanceTree[] nodes = draw.nodes;
        int[] map = draw.nodeToBody;
        for (int i = 0; i < nodes.length; i++) {
            int bi = map[i];
            if (bi < 0) {
                continue;
            }
            InstanceTree node = nodes[i];
            int b = bi * 9;
            node.pos(transforms[b], transforms[b + 1], transforms[b + 2]);
            node.rotation(transforms[b + 3], transforms[b + 4], transforms[b + 5]);
            float f = i == 0 ? 1.0F : inflate;
            node.scale(transforms[b + 6] * f, transforms[b + 7] * f, transforms[b + 8] * f);
        }
        draw.tree.updateInstances(root);
        int drawLight = draw.emissive ? LightCoordsUtil.FULL_BRIGHT : light;
        draw.tree.traverse(instance -> {
            instance.light(drawLight);
            instance.overlay(overlayCoords);
            instance.colorArgb(color);
            instance.setChanged();
        });
    }

    private static void flattenNamed(InstanceTree node, String name, List<InstanceTree> nodes, List<String> names) {
        nodes.add(node);
        names.add(name);
        for (int i = 0; i < node.childCount(); i++) {
            flattenNamed(node.child(i), node.childName(i), nodes, names);
        }
    }

    void apply(long conditionMask, int @Nullable [] colors, Identifier @Nullable [] textures, float[] transforms,
               Matrix4f root, int light, int overlayCoords) {
        for (int i = 0; i < draws.length; i++) {
            Draw draw = draws[i];
            if ((conditionMask & (1L << i)) == 0) {
                setShown(draw, false);
                continue;
            }
            if (draw.dynamicKind != null) {
                Identifier texture = textures == null ? null : textures[i];
                if (texture == null) {
                    setShown(draw, false);
                    continue;
                }
                if (!texture.equals(draw.currentTexture)) {
                    rebuild(draw, texture);
                }
            }
            if (draw.tree == null) {
                continue;
            }
            setShown(draw, true);
            pose(draw, 1.0F + INFLATE_STEP * i, transforms, root, light, overlayCoords,
                    colors == null ? -1 : colors[i]);
        }
    }

    void hide() {
        for (Draw draw : draws) {
            setShown(draw, false);
        }
    }

    void delete() {
        for (Draw draw : draws) {
            if (draw.tree != null) {
                draw.tree.delete();
            }
        }
    }

    private void rebuild(Draw draw, Identifier texture) {
        if (draw.tree != null) {
            draw.tree.delete();
        }
        build(draw, InstanceTree.create(provider,
                ModelTrees.of(draw.layer, LivingEntityVisual.dynamicOverlayMaterial(texture, draw.dynamicKind))));
        draw.currentTexture = texture;
    }

    private void build(Draw draw, InstanceTree tree) {
        List<InstanceTree> nodeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        flattenNamed(tree, "", nodeList, nameList);
        int[] map = new int[nodeList.size()];
        for (int j = 0; j < map.length; j++) {
            map[j] = boneIndex.getOrDefault(nameList.get(j), -1);
        }
        draw.tree = tree;
        draw.nodes = nodeList.toArray(new InstanceTree[0]);
        draw.nodeToBody = map;
        draw.shown = true;
    }

    private static final class Draw {
        final ModelLayerLocation layer;
        final boolean emissive;
        @Nullable
        final OverlayKind dynamicKind;
        @Nullable
        InstanceTree tree;
        InstanceTree[] nodes = new InstanceTree[0];
        int[] nodeToBody = new int[0];
        @Nullable
        Identifier currentTexture;
        boolean shown;

        Draw(ModelLayerLocation layer, boolean emissive, @Nullable OverlayKind dynamicKind) {
            this.layer = layer;
            this.emissive = emissive;
            this.dynamicKind = dynamicKind;
        }
    }
}
