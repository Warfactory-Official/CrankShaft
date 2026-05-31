package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.EmissiveLayer;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelEnderman;
import net.minecraft.entity.monster.EntityEnderman;
import org.joml.Matrix4f;

import java.util.Random;

/** Enderman: body, emissive eyes, carried block ({@link EndermanHeldBlockLayer}); a screaming one jitters position. */
public final class EndermanVisual extends SimpleLivingEntityVisual<EntityEnderman, ModelEnderman> {
    private final Random rng = new Random();

    public EndermanVisual(VisualizationContext ctx, EntityEnderman entity, float partialTick,
                          EntityModel<ModelEnderman> model, Material body, Material eyes, String cacheKey, float shadowRadius) {
        super(ctx, entity, partialTick, model, body, cacheKey, shadowRadius, 1.0F, 90.0F);
        addLayer(new EmissiveLayer<>(ctx, instances, model, eyes, cacheKey + ":eyes", 1));
        addLayer(new EndermanHeldBlockLayer(ctx, entity, 2));
    }

    @Override
    protected void poseModel(float partialTick) {
        // RenderEnderman.doRender sets these before posing.
        model.isCarrying = entity.getHeldBlockState() != null;
        model.isAttacking = entity.isScreaming();
        super.poseModel(partialTick);
    }

    @Override
    protected void buildRoot(Matrix4f dest, float partialTick) {
        super.buildRoot(dest, partialTick);
        if (entity.isScreaming()) {
            // Vanilla jitters world X/Z by nextGaussian()*0.02; translateLocal pre-multiplies (world space).
            dest.translateLocal((float) (rng.nextGaussian() * 0.02), 0.0F, (float) (rng.nextGaussian() * 0.02));
        }
    }

    @Override
    protected void applyModelTransform(Matrix4f dest) {
        // ModelEnderman inherits ModelBiped.render's sneak drop.
        if (entity.isSneaking()) {
            dest.translate(0.0F, 0.2F, 0.0F);
        }
    }
}
