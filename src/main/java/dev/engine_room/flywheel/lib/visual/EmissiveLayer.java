package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.client.model.ModelBase;
import org.joml.Matrix4fc;

/**
 * A fullbright emissive overlay (spider/enderman/blaze eyes): the body's geometry re-instanced with an
 * emissive texture at a higher bias, posed identically to the body each frame. The overlay shares the
 * body's bone structure (same {@link EntityModel}), so it copies the body's posed bones one-to-one.
 */
public final class EmissiveLayer<M extends ModelBase> implements LivingLayer {
    private final InstanceTree body;
    private final InstanceTree overlay;
    // !posed ⇒ the overlay is hidden and must be revealed+seeded+posed by the next beginFrame. Construction
    // seeds the instances at identity (the render origin), so they stay hidden until they can be posed —
    // beginFrame only runs for un-culled visuals, which keeps a culled spawn from ghosting eyes at the origin.
    private boolean posed;

    public EmissiveLayer(VisualizationContext ctx, InstanceTree body, EntityModel<M> model, Material material, String cacheKey, int bias) {
        this.body = body;
        this.overlay = InstanceTree.create(ctx.instancerProvider(), AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias);
        overlay.visible(false);
    }

    private void seedFullbright() {
        overlay.traverse(i -> {
            i.overlay(OverlayTexture.NO_OVERLAY);
            i.light(LightTexture.FULL_BRIGHT);
        });
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (posed && !bodyMoved) {
            return;
        }
        if (!posed) {
            overlay.visible(true);
            // Reveal reseeds the freed slots (light 0, identity); re-seed fullbright before posing.
            seedFullbright();
        }
        overlay.copyComposedFrom(body);
        posed = true;
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            overlay.visible(false);
        }
        // Reveal defers to beginFrame (see posed) so a still-culled entity can't draw an unposed overlay.
        posed = false;
    }

    @Override
    public void delete() {
        overlay.delete();
    }
}
