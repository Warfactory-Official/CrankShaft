package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelPolarBear;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelPolarBear} — quadruped roots, but the cub transform scales the
 *  head ({@link QuadrupedEntityModel}'s child split does not). */
public final class PolarBearEntityModel implements EntityModel<ModelPolarBear> {
    private static final float MODEL_SCALE = 0.0625F;

    @Override
    public ModelPolarBear create() {
        return new ModelPolarBear();
    }

    @Override
    public ModelRenderer[] roots(ModelPolarBear m) {
        return new ModelRenderer[] { m.head, m.body, m.leg1, m.leg2, m.leg3, m.leg4 };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelPolarBear m, int rootIndex) {
        // Per ModelPolarBear.render: the cub head is 2/3 scale at (0,16,4) offsets; the rest is the 0.5 group.
        if (rootIndex == 0) {
            dest.scale(0.6666667F).translate(0.0F, 16.0F * MODEL_SCALE, 4.0F * MODEL_SCALE);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
