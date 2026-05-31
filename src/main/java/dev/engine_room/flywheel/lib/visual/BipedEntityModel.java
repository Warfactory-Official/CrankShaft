package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

import java.util.function.Supplier;

/** {@link EntityModel} for the seven flat {@link ModelBiped} parts (zombie, skeleton, husk, …). */
public final class BipedEntityModel<M extends ModelBiped> implements EntityModel<M> {
    private static final float MODEL_SCALE = 0.0625F;
    private final Supplier<M> factory;

    public BipedEntityModel(Supplier<M> factory) {
        this.factory = factory;
    }

    @Override
    public M create() {
        return factory.get();
    }

    @Override
    public ModelRenderer[] roots(M m) {
        return new ModelRenderer[] {
                m.bipedHead, m.bipedBody,
                m.bipedRightArm, m.bipedLeftArm, m.bipedRightLeg, m.bipedLeftLeg,
                m.bipedHeadwear,
        };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, M m, int rootIndex) {
        // Per ModelBiped.render: only bipedHead (root 0) uses the 0.75 head scale; headwear stays in the 0.5 body group.
        if (rootIndex == 0) {
            dest.scale(0.75F).translate(0.0F, 16.0F * MODEL_SCALE, 0.0F);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
