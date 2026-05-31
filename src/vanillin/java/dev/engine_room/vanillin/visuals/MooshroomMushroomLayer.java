package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.init.Blocks;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Mooshroom's three red-mushroom decorations: the {@code RED_MUSHROOM} block model ({@code LayerMooshroomMushroom},
 * rendered fullbright via {@code renderBlockBrightness}) instanced at two body-fixed attach points and one on the
 * head bone (so it tracks the cow's head). Vanilla single-face-culls the flipped model; we use a no-cull block
 * material instead, which is equivalent for the doubled-quad cross model.
 */
public final class MooshroomMushroomLayer implements LivingLayer {
    // 1.12.2: head is index 0 in QuadrupedEntityModel.roots {head, body, leg1..4}.
    private static final int HEAD_BONE = 0;

    private final EntityMooshroom entity;
    private final InstanceTree body;
    private final TransformedInstance[] mushrooms = new TransformedInstance[3];
    private final Matrix4f base = new Matrix4f();
    private final Matrix4f scratch = new Matrix4f();
    private boolean parentVisible = true;
    private boolean shown;
    private int lastOverlay = -1;

    public MooshroomMushroomLayer(VisualizationContext ctx, EntityMooshroom entity, InstanceTree body, int bias) {
        this.entity = entity;
        this.body = body;
        InstancerProvider provider = ctx.instancerProvider();
        Model model = EntityMaterials.BLOCK_OVERLAY_MODELS.get(Blocks.RED_MUSHROOM.getDefaultState());
        for (int i = 0; i < mushrooms.length; i++) {
            mushrooms[i] = provider.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
            // Seeded at the render origin; stay hidden until the first un-culled beginFrame poses them.
            mushrooms[i].setVisible(false);
        }
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        // Vanilla LayerMooshroomMushroom skips babies.
        boolean show = parentVisible && !entity.isChild();
        if (show != shown) {
            shown = show;
            for (TransformedInstance m : mushrooms) {
                m.setVisible(show);
            }
            if (show) {
                // Reveal reseeds the freed slab slots; force the guarded push below.
                lastOverlay = -1;
            }
        }
        if (!show) {
            return;
        }

        // Light is constant FULL_BRIGHT and color/uv constant, so the only per-frame state delta is overlay.
        int overlay = OverlayTexture.forEntity(entity);
        if (!bodyMoved && overlay == lastOverlay) {
            return;
        }
        lastOverlay = overlay;

        // Two body-fixed mushrooms share a base frame off the root (vanilla LayerMooshroomMushroom).
        // The terminal rotateY(90) is the implicit rotate(90,Y) that BlockModelRenderer.renderModelBrightness
        // applies to block quads before drawing — appended last so it's innermost (applied first to vertices).
        base.set(rootPose).scale(1.0F, -1.0F, 1.0F).translate(0.2F, 0.35F, 0.5F).rotateY((float) Math.toRadians(42.0));
        scratch.set(base).translate(-0.5F, -0.5F, 0.5F).rotateY((float) Math.toRadians(90.0));
        mushrooms[0].setTransform(scratch);
        scratch.set(base).translate(0.1F, 0.0F, -0.6F).rotateY((float) Math.toRadians(42.0)).translate(-0.5F, -0.5F, 0.5F).rotateY((float) Math.toRadians(90.0));
        mushrooms[1].setTransform(scratch);
        scratch.set(body.child(HEAD_BONE).poseMatrix())
                .scale(1.0F, -1.0F, 1.0F).translate(0.0F, 0.7F, -0.2F)
                .rotateY((float) Math.toRadians(12.0)).translate(-0.5F, -0.5F, 0.5F).rotateY((float) Math.toRadians(90.0));
        mushrooms[2].setTransform(scratch);

        // renderBlockBrightness(state, 1.0F) draws the mushrooms fullbright; they red-flash with the cow.
        for (TransformedInstance m : mushrooms) {
            m.light(LightTexture.FULL_BRIGHT);
            m.overlay(overlay);
            m.colorArgb(0xFFFFFFFF);
            m.setChanged();
        }
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            for (TransformedInstance m : mushrooms) {
                m.setVisible(false);
            }
        }
    }

    @Override
    public void delete() {
        for (TransformedInstance m : mushrooms) {
            m.delete();
        }
    }
}
