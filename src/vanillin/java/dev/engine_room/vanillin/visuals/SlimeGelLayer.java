package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.model.ModelSlime;
import net.minecraft.entity.monster.EntitySlime;
import org.joml.Matrix4fc;

/**
 * Slime's translucent outer gel: {@code ModelSlime(0)}'s single 8×8×8 cube re-instanced over the inner body
 * at a higher bias with a translucent material (vanilla {@code LayerSlimeGel}, alpha-blended). The slime model
 * has no per-bone animation, so the gel rides the body's root pose (which already carries the size + squish)
 * directly — no bone copy. The gel red-flashes with the body ({@code shouldCombineTextures}).
 */
public final class SlimeGelLayer implements LivingLayer {
    private final EntitySlime slime;
    private final InstanceTree gel;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;

    public SlimeGelLayer(VisualizationContext ctx, EntitySlime slime, EntityModel<ModelSlime> gelModel,
                         Material material, String cacheKey, int bias) {
        this.slime = slime;
        this.gel = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(gelModel, material, cacheKey), bias);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        gel.visible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (!parentVisible) {
            return;
        }
        if (!shown) {
            shown = true;
            gel.visible(true);
            // Revealing reseeds the freed slab slots (white/light-0); force the guarded traverse to re-push.
            lastLight = Integer.MIN_VALUE;
            lastOverlay = -1;
        }
        int overlay = OverlayTexture.forEntity(slime);
        if (!bodyMoved && light == lastLight && overlay == lastOverlay) {
            return;
        }

        if (light != lastLight || overlay != lastOverlay) {
            lastLight = light;
            lastOverlay = overlay;
            gel.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(0xFFFFFFFF);
            });
        }
        gel.updateInstances(rootPose);
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            gel.visible(false);
        }
    }

    @Override
    public void delete() {
        gel.delete();
    }
}
