package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.model.ModelWither;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4fc;

/**
 * Wither charging aura (vanilla {@code LayerWitherAura}). {@link InstanceTypes#UV_TRANSFORMED} turns vanilla's
 * per-frame UV scroll into a per-instance offset; {@code wither_armor.png} is GL_REPEAT so scrolled coords tile.
 */
public final class WitherAuraLayer implements LivingLayer {
    private static final int AURA_TINT = 0x80808080;

    private final EntityWither wither;
    private final InstanceTree body;
    private final InstanceTree aura;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;

    public WitherAuraLayer(VisualizationContext ctx, EntityWither wither, InstanceTree body,
                           EntityModel<ModelWither> model, Material material, String cacheKey, int bias) {
        this.wither = wither;
        this.body = body;
        this.aura = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias, InstanceTypes.UV_TRANSFORMED);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        aura.visible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && wither.isArmored();
        if (show != shown) {
            shown = show;
            aura.visible(show);
            if (show) {
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
            }
        }
        if (!show) {
            return;
        }

        // Vanilla scrolls the texture matrix by (cos(f*0.02)*3, f*0.01); replayed as a per-instance UV offset.
        float f = wither.ticksExisted + partialTick;
        applyUvScroll(aura, MathHelper.cos(f * 0.02F) * 3.0F, f * 0.01F);

        int overlay = OverlayTexture.forEntity(wither);
        if (light != lastLight || overlay != lastOverlay) {
            lastLight = light;
            lastOverlay = overlay;
            aura.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(AURA_TINT);
            });
        }
        aura.copyComposedFrom(body);
    }

    // Static walk so the every-frame scroll doesn't allocate a capturing lambda (the gated light push is rare).
    private static void applyUvScroll(InstanceTree node, float offU, float offV) {
        TransformedInstance inst = node.instance();
        if (inst != null) {
            ((UvTransformedInstance) inst).uvRegion(offU, offV, 1.0F, 1.0F);
        }
        for (int i = 0, n = node.childCount(); i < n; i++) {
            applyUvScroll(node.child(i), offU, offV);
        }
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            aura.visible(false);
        }
    }

    @Override
    public void delete() {
        aura.delete();
    }
}
