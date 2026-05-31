package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.HeldItemLayer;
import net.minecraft.client.model.ModelIllager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.AbstractIllager;
import org.joml.Matrix4f;

import java.util.function.Predicate;

/**
 * Shared visual for {@link ModelIllager} mobs (evoker, vindicator). Masks the combined-arms vs separate-arms
 * node sets each frame per {@code getArmPose()} (vanilla renders one or the other), hides the legacy hat, scales
 * 0.9375, and attaches a primary-hand {@link HeldItemLayer} gated by a caster-supplied predicate (spellcasting
 * for the evoker, aggressive for the vindicator) so the item only shows when the arms are out.
 */
public final class IllagerVisual extends AbstractLivingEntityVisual<AbstractIllager, ModelIllager> {
    public IllagerVisual(VisualizationContext ctx, AbstractIllager entity, float partialTick,
                         EntityModel<ModelIllager> model, Material material, String cacheKey, float shadowRadius,
                         Predicate<EntityLivingBase> heldItemVisible) {
        super(ctx, entity, partialTick, model, material, cacheKey, shadowRadius);
        // The illager hat is showModel=false in vanilla; the baker ignores showModel, so hide it (head child 0).
        instances.child(0).child(0).skipDraw(true);
        // Primary hand is rightArm (root index 5); item drawn after the body, only while the predicate holds.
        addLayer(new HeldItemLayer(ctx, entity, instances, 5, 1, heldItemVisible));
    }

    @Override
    protected void poseModel(float partialTick) {
        super.poseModel(partialTick);
        // render() draws the combined `arms` node when CROSSED, else the two separate arms — mask the other set.
        boolean crossed = entity.getArmPose() == AbstractIllager.IllagerArmPose.CROSSED;
        setRootSkipDraw(4, !crossed);
        setRootSkipDraw(5, crossed);
        setRootSkipDraw(6, crossed);
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.9375F);
    }
}
