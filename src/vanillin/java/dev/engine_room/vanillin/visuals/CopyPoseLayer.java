package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4fc;

// Shared scaffolding for copy-pose overlay layers that re-instance a model sharing the body's bones
// (sheep wool, wolf collar, stray clothing): mirror the body's composed pose one-to-one and push a
// per-instance color/light/overlay only when one of those changed. Subclasses supply the show predicate
// and the per-instance color. Layers with extra per-frame state (UV scroll) do not fit this skeleton.
abstract class CopyPoseLayer implements LivingLayer {
    private final EntityLivingBase entity;
    protected final InstanceTree body;
    protected final InstanceTree overlay;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastColor = 0;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;

    protected CopyPoseLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body,
                            ModelTree modelTree, int bias) {
        this.entity = entity;
        this.body = body;
        this.overlay = InstanceTree.create(ctx.instancerProvider(), modelTree, bias);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        overlay.visible(false);
    }

    /** Whether the overlay should render this frame; gated additionally by the parent's visibility. */
    protected boolean show() {
        return true;
    }

    /** Per-instance ARGB tint for this frame. */
    protected abstract int color(float partialTick);

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && show();
        if (show != shown) {
            shown = show;
            overlay.visible(show);
            if (show) {
                // Revealing reseeds the freed slab slots; force the guarded traverse below to re-push
                // color/light/overlay. copyComposedFrom already restores the pose.
                lastColor = 0;
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
            }
        }
        if (!show) {
            return;
        }

        int color = color(partialTick);
        int overlayCoord = OverlayTexture.forEntity(entity);
        if (!bodyMoved && color == lastColor && light == lastLight && overlayCoord == lastOverlay) {
            return;
        }

        if (color != lastColor || light != lastLight || overlayCoord != lastOverlay) {
            lastColor = color;
            lastLight = light;
            lastOverlay = overlayCoord;
            overlay.traverse(i -> {
                i.colorArgb(color);
                i.light(light);
                i.overlay(overlayCoord);
            });
        }
        overlay.copyComposedFrom(body);
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            overlay.visible(false);
        }
    }

    @Override
    public void delete() {
        overlay.delete();
    }
}
