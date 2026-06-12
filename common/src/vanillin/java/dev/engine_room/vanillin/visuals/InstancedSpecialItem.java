package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.vanillin.item.SpecialItemModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A worker-side slot drawing the resolved special-model layers of one item ({@link SpecialItemModels}).
 * Pattern stacks draw one OIT tree per layer, bones inflated 0.1% per step about their own pivots, so the OIT
 * resolve recovers vanilla's submit-order paint order (a root-scale would shift geometry off-origin).
 */
final class InstancedSpecialItem {
    private static final Material SHIELD_BASE_MATERIAL = SimpleMaterial.builder()
                                                                       .mipmap(false)
                                                                       .texture(Sheets.SHIELD_SHEET)
                                                                       .build();
    private static final Material BANNER_BASE_MATERIAL = SimpleMaterial.builder()
                                                                       .mipmap(false)
                                                                       .texture(Sheets.BANNER_SHEET)
                                                                       .build();
    private static final Material SHIELD_PATTERN_MATERIAL = SimpleMaterial.builder()
                                                                          .transparency(Transparency.ORDER_INDEPENDENT)
                                                                          .writeMask(WriteMask.COLOR)
                                                                          .mipmap(false)
                                                                          .texture(Sheets.SHIELD_SHEET)
                                                                          .build();
    private static final Material BANNER_PATTERN_MATERIAL = SimpleMaterial.builder()
                                                                          .transparency(Transparency.ORDER_INDEPENDENT)
                                                                          .writeMask(WriteMask.COLOR)
                                                                          .mipmap(false)
                                                                          .texture(Sheets.BANNER_SHEET)
                                                                          .build();

    private static final float PATTERN_INFLATE_STEP = 0.001F;

    private final InstancerProvider provider;
    private final Matrix4f scratch = new Matrix4f();
    private final List<Draw> draws = new ArrayList<>();
    private List<SpecialItemModels.Resolved> current = List.of();
    private boolean hidden;

    InstancedSpecialItem(InstancerProvider provider) {
        this.provider = provider;
    }

    private static void posed(Draw draw, float @Nullable [] fixedPose) {
        if (fixedPose != null) {
            if (fixedPose.length != draw.nodes.length * 9) {
                throw new IllegalStateException("special-item model/bake tree mismatch");
            }
            poseNodes(draw.nodes, fixedPose);
        }
    }

    private static void poseNodes(InstanceTree[] nodes, float[] pose) {
        for (int i = 0; i < nodes.length; i++) {
            InstanceTree node = nodes[i];
            int b = i * 9;
            node.pos(pose[b], pose[b + 1], pose[b + 2]);
            node.rotation(pose[b + 3], pose[b + 4], pose[b + 5]);
            node.scale(pose[b + 6], pose[b + 7], pose[b + 8]);
        }
    }

    private static void flattenTree(InstanceTree node, List<InstanceTree> out) {
        out.add(node);
        for (int i = 0; i < node.childCount(); i++) {
            flattenTree(node.child(i), out);
        }
    }

    private static ModelLayerLocation skullLayer(SkullBlock.Type type) {
        return switch ((SkullBlock.Types) type) {
            case SKELETON -> ModelLayers.SKELETON_SKULL;
            case WITHER_SKELETON -> ModelLayers.WITHER_SKELETON_SKULL;
            case PLAYER -> ModelLayers.PLAYER_HEAD;
            case ZOMBIE -> ModelLayers.ZOMBIE_HEAD;
            case CREEPER -> ModelLayers.CREEPER_HEAD;
            case DRAGON -> ModelLayers.DRAGON_SKULL;
            case PIGLIN -> ModelLayers.PIGLIN_HEAD;
        };
    }

    private static TextureAtlasSprite sprite(Identifier atlasId, SpriteId spriteId) {
        return Minecraft.getInstance()
                        .getAtlasManager()
                        .getAtlasOrThrow(atlasId)
                        .getSprite(spriteId.texture());
    }

    void apply(List<SpecialItemModels.Resolved> resolved, float skullAnimation, Matrix4f world, int light,
               int overlay) {
        if (current != resolved && !current.equals(resolved)) {
            rebuild(resolved);
        }
        if (hidden) {
            for (Draw draw : draws) {
                draw.tree.visible(true);
            }
            hidden = false;
        }
        for (Draw draw : draws) {
            if (draw.skull != null) {
                poseSkull(draw, skullAnimation);
            }
            draw.tree.updateInstances(scratch.set(world).mul(draw.transform));
            int drawOverlay = draw.useOverlay ? overlay : OverlayTexture.NO_OVERLAY;
            int color = draw.color;
            draw.tree.traverse(instance -> {
                instance.light(light);
                instance.overlay(drawOverlay);
                instance.colorArgb(color);
                instance.setChanged();
            });
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
        draws.clear();
    }

    private void rebuild(List<SpecialItemModels.Resolved> resolved) {
        for (Draw draw : draws) {
            draw.tree.delete();
        }
        draws.clear();
        hidden = false;
        current = List.copyOf(resolved);
        for (SpecialItemModels.Resolved layer : resolved) {
            switch (layer.key()) {
                case SpecialItemModels.TridentKey k -> {
                    add(ModelTrees.of(ModelLayers.TRIDENT, TridentVisual.MATERIAL), layer.transform(), -1, true);
                    if (k.foil()) {
                        add(ModelTrees.of(ModelLayers.TRIDENT, Materials.GLINT_ENTITY), layer.transform(), -1, true);
                    }
                }
                case SpecialItemModels.SkullKey k -> addSkull(k, layer.transform());
                case SpecialItemModels.ShieldKey k -> {
                    boolean patterned = !k.patterns().layers().isEmpty() || k.baseColor() != null;
                    TextureAtlasSprite base = sprite(AtlasIds.SHIELD_PATTERNS,
                            patterned ? Sheets.SHIELD_BASE : Sheets.SHIELD_BASE_NO_PATTERN);
                    add(ModelTrees.of(ModelLayers.SHIELD, base, SHIELD_BASE_MATERIAL), layer.transform(), -1, true);
                    if (patterned) {
                        addPatterns(ModelLayers.SHIELD, false, k.baseColor() == null ? DyeColor.WHITE : k.baseColor(),
                                k.patterns(), layer.transform(), null);
                    }
                    if (k.foil()) {
                        add(ModelTrees.of(ModelLayers.SHIELD, base, Materials.GLINT_ENTITY), layer.transform(), -1,
                                true);
                    }
                }
                case SpecialItemModels.BannerKey k -> {
                    TextureAtlasSprite base = sprite(AtlasIds.BANNER_PATTERNS, Sheets.BANNER_BASE);
                    add(ModelTrees.of(ModelLayers.STANDING_BANNER, base, BANNER_BASE_MATERIAL), layer.transform(), -1,
                            true);
                    float[] flagPose = flagPose();
                    posed(add(ModelTrees.of(ModelLayers.STANDING_BANNER_FLAG, base, BANNER_BASE_MATERIAL),
                            layer.transform(), -1, true), flagPose);
                    addPatterns(ModelLayers.STANDING_BANNER_FLAG, true, k.baseColor(), k.patterns(), layer.transform(),
                            flagPose);
                }
            }
        }
    }

    private void addPatterns(ModelLayerLocation layerLoc, boolean banner, DyeColor baseColor,
                             BannerPatternLayers patterns,
                             Matrix4fc transform, float @Nullable [] fixedPose) {
        Identifier atlas = banner ? AtlasIds.BANNER_PATTERNS : AtlasIds.SHIELD_PATTERNS;
        Material material = banner ? BANNER_PATTERN_MATERIAL : SHIELD_PATTERN_MATERIAL;
        SpriteId baseSprite = banner ? Sheets.BANNER_PATTERN_BASE : Sheets.SHIELD_PATTERN_BASE;
        addPatternLayer(layerLoc, atlas, material, baseSprite, baseColor.getTextureDiffuseColor(), 1, banner, transform,
                fixedPose);
        for (int i = 0; i < 16 && i < patterns.layers().size(); i++) {
            BannerPatternLayers.Layer patternLayer = patterns.layers().get(i);
            SpriteId sprite = banner ? Sheets.getBannerSprite(patternLayer.pattern()) : Sheets.getShieldSprite(
                    patternLayer.pattern());
            addPatternLayer(layerLoc, atlas, material, sprite, patternLayer.color().getTextureDiffuseColor(), i + 2,
                    banner, transform, fixedPose);
        }
    }

    private void addPatternLayer(ModelLayerLocation layerLoc, Identifier atlas, Material material, SpriteId spriteId,
                                 int color, int steps, boolean banner, Matrix4fc transform,
                                 float @Nullable [] fixedPose) {
        Draw draw = add(ModelTrees.of(layerLoc, sprite(atlas, spriteId), material), transform, color, false);
        if (fixedPose != null && fixedPose.length != draw.nodes.length * 9) {
            throw new IllegalStateException("special-item model/bake tree mismatch");
        }
        float f = 1.0F + PATTERN_INFLATE_STEP * steps;
        float dy = (banner ? 20.0F : 0.0F) * (1.0F - f);
        float dz = -1.5F * (1.0F - f);
        for (int i = 0; i < draw.nodes.length; i++) {
            InstanceTree node = draw.nodes[i];
            if (fixedPose != null) {
                int b = i * 9;
                node.pos(fixedPose[b], fixedPose[b + 1] + (i == 0 ? 0.0F : dy),
                        fixedPose[b + 2] + (i == 0 ? 0.0F : dz));
                node.rotation(fixedPose[b + 3], fixedPose[b + 4], fixedPose[b + 5]);
            } else if (i > 0) {
                node.pos(0.0F, dy, dz);
            }
            if (i > 0) {
                node.scale(f, f, f);
            }
        }
    }

    private void addSkull(SpecialItemModels.SkullKey key, Matrix4fc transform) {
        SkullModelBase model = SkullBlockRenderer.createModel(Minecraft.getInstance().getEntityModels(), key.type());
        if (model == null) {
            return;
        }
        Material material = key.translucentSkin()
                ? LivingEntityVisual.translucentBodyMaterial(key.texture())
                : LivingEntityVisual.equipmentMaterial(key.texture());
        Draw draw = add(ModelTrees.of(skullLayer(key.type()), material), transform, -1, true);
        List<ModelPart> parts = new ArrayList<>();
        EntityModelVisual.flattenModel(model.root(), "", -1, parts, new ArrayList<>(), new ArrayList<>());
        draw.skull = model;
        draw.skullParts = parts.toArray(new ModelPart[0]);
        draw.skullState = new SkullModelBase.State();
        if (draw.skullParts.length != draw.nodes.length) {
            throw new IllegalStateException("skull model/bake tree mismatch: " + key.type());
        }
    }

    private void poseSkull(Draw draw, float animation) {
        draw.skullState.animationPos = animation;
        draw.skull.setupAnim(draw.skullState);
        float[] pose = LivingEntityVisual.posedFloats(draw.skullParts);
        poseNodes(draw.nodes, pose);
    }

    private Draw add(ModelTree modelTree, Matrix4fc transform, int color, boolean useOverlay) {
        InstanceTree tree = InstanceTree.create(provider, modelTree);
        List<InstanceTree> nodes = new ArrayList<>();
        flattenTree(tree, nodes);
        Draw draw = new Draw(tree, nodes.toArray(new InstanceTree[0]), new Matrix4f(transform), color, useOverlay);
        draws.add(draw);
        return draw;
    }

    private float[] flagPose() {
        BannerFlagModel flag = new BannerFlagModel(Minecraft.getInstance()
                                                            .getEntityModels()
                                                            .bakeLayer(ModelLayers.STANDING_BANNER_FLAG));
        flag.setupAnim(0.0F);
        List<ModelPart> parts = new ArrayList<>();
        EntityModelVisual.flattenModel(flag.root(), "", -1, parts, new ArrayList<>(), new ArrayList<>());
        return LivingEntityVisual.posedFloats(parts.toArray(new ModelPart[0]));
    }

    private static final class Draw {
        final InstanceTree tree;
        final InstanceTree[] nodes;
        final Matrix4f transform;
        final int color;
        final boolean useOverlay;
        @Nullable
        SkullModelBase skull;
        ModelPart[] skullParts;
        SkullModelBase.State skullState;

        Draw(InstanceTree tree, InstanceTree[] nodes, Matrix4f transform, int color, boolean useOverlay) {
            this.tree = tree;
            this.nodes = nodes;
            this.transform = transform;
            this.color = color;
            this.useOverlay = useOverlay;
        }
    }
}
