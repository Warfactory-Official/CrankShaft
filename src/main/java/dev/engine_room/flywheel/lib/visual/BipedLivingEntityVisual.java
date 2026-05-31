package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4f;

/**
 * A {@link SimpleLivingEntityVisual} for biped mobs with the standard biped layers attached: a main-hand
 * {@link HeldItemLayer} (on {@code bipedRightArm}) and an {@link ArmorLayer}. Use for zombies, skeletons, and
 * their variants. The {@link EntityModel} MUST be a {@link BipedEntityModel} — the layers rely on its 7-root
 * bone order (arm at index 2).
 */
public class BipedLivingEntityVisual<T extends EntityLivingBase, M extends ModelBiped>
        extends SimpleLivingEntityVisual<T, M> {
    public BipedLivingEntityVisual(VisualizationContext ctx, T entity, float partialTick,
                                   EntityModel<M> model, Material material, String cacheKey,
                                   float shadowRadius, float uniformScale, float deathMaxRotation) {
        super(ctx, entity, partialTick, model, material, cacheKey, shadowRadius, uniformScale, deathMaxRotation, true);
        // Arm bones 2/3, armor bias 2 in BipedEntityModel.roots.
        addLayer(new HeldItemLayer(ctx, entity, instances, 2, 3, 1));
        addLayer(new ArmorLayer(ctx, entity, instances, 2));
    }

    @Override
    protected boolean shouldHide() {
        return super.shouldHide() || ArmorModels.hasCustomArmorModel(entity);
    }

    @Override
    protected void applyModelTransform(Matrix4f dest) {
        // ModelBiped.render: only the non-child branch drops the body 0.2 in the flipped model frame when
        // sneaking (the child branch has no such translate). The held item rides the arm bone composed from
        // this root, so HeldItemLayer drops nothing.
        if (entity.isSneaking() && !entity.isChild()) {
            dest.translate(0.0F, 0.2F, 0.0F);
        }
    }
}
