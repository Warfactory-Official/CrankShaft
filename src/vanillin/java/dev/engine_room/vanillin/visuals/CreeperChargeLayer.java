package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.CreeperEntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.entity.monster.EntityCreeper;
import org.joml.Matrix4fc;

/**
 * Charged creeper aura (vanilla {@code LayerCreeperCharge}). {@link InstanceTypes#UV_TRANSFORMED} turns vanilla's
 * per-frame UV scroll into a per-instance offset; {@code creeper_armor.png} is GL_REPEAT so scrolled coords tile.
 */
public final class CreeperChargeLayer implements LivingLayer {
    private static final int AURA_TINT = 0x80808080;

    private final EntityCreeper creeper;
    private final InstanceTree body;
    private final InstanceTree aura;

    private boolean parentVisible = true;
    private boolean shown;

    public CreeperChargeLayer(VisualizationContext ctx, EntityCreeper creeper, InstanceTree body,
                              Material material, String cacheKey, int bias) {
        this.creeper = creeper;
        this.body = body;
        this.aura = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(new CreeperEntityModel(2.0F), material, cacheKey),
                bias, InstanceTypes.UV_TRANSFORMED);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        aura.visible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && creeper.getPowered();
        if (show != shown) {
            shown = show;
            aura.visible(show);
        }
        if (!show) {
            return;
        }

        // Vanilla scrolls U/V by f*0.01 per frame.
        float off = (creeper.ticksExisted + partialTick) * 0.01F;
        applyAura(aura, off, light, OverlayTexture.forEntity(creeper));
        aura.copyComposedFrom(body);
    }

    // Static walk so the every-frame scroll doesn't allocate a capturing lambda.
    private static void applyAura(InstanceTree node, float off, int light, int overlay) {
        TransformedInstance inst = node.instance();
        if (inst != null) {
            inst.light(light);
            inst.overlay(overlay);
            inst.colorArgb(AURA_TINT);
            ((UvTransformedInstance) inst).uvRegion(off, off, 1.0F, 1.0F);
        }
        for (int i = 0, n = node.childCount(); i < n; i++) {
            applyAura(node.child(i), off, light, overlay);
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
