package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.model.ModelPig;
import net.minecraft.entity.passive.EntityPig;
import org.joml.Matrix4fc;

/** Pig saddle (vanilla {@code LayerSaddle}), shown while {@code getSaddled()}. {@code ModelPig(0.5)} and
 *  {@code ModelPig(0.0)} share bone transforms, so the saddle copies the body's posed bones rather than reposing. */
public final class SaddleLayer implements LivingLayer {
    private final EntityPig pig;
    private final InstanceTree body;
    private final InstanceTree saddle;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;

    public SaddleLayer(VisualizationContext ctx, EntityPig pig, InstanceTree body,
                       EntityModel<ModelPig> model, Material material, String cacheKey, int bias) {
        this.pig = pig;
        this.body = body;
        this.saddle = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        saddle.visible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && pig.getSaddled();
        if (show != shown) {
            shown = show;
            saddle.visible(show);
            if (show) {
                // Reveal reseeds freed slab slots to white/light-0; force the guarded traverse to re-push.
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
            }
        }
        if (!show) {
            return;
        }

        int overlay = OverlayTexture.forEntity(pig);
        if (!bodyMoved && light == lastLight && overlay == lastOverlay) {
            return;
        }

        if (light != lastLight || overlay != lastOverlay) {
            lastLight = light;
            lastOverlay = overlay;
            saddle.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(0xFFFFFFFF);
            });
        }
        saddle.copyComposedFrom(body);
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            saddle.visible(false);
        }
    }

    @Override
    public void delete() {
        saddle.delete();
    }
}
