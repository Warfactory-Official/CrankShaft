package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelQuadruped;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

import java.util.function.Supplier;

/** {@link EntityModel} for {@link ModelQuadruped}'s six flat parts (pig, cow, …). */
public final class QuadrupedEntityModel<M extends ModelQuadruped> implements EntityModel<M> {
    private static final float MODEL_SCALE = 0.0625F;

    private final Supplier<M> factory;

    public QuadrupedEntityModel(Supplier<M> factory) {
        this.factory = factory;
    }

    @Override
    public M create() {
        return factory.get();
    }

    @Override
    public ModelRenderer[] roots(M m) {
        return new ModelRenderer[] { m.head, m.body, m.leg1, m.leg2, m.leg3, m.leg4 };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, M m, int rootIndex) {
        // Per ModelQuadruped.render: head (root 0) only translates (no scale); the body group is scale(0.5).
        if (rootIndex == 0) {
            dest.translate(0.0F, m.childYOffset * MODEL_SCALE, m.childZOffset * MODEL_SCALE);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
