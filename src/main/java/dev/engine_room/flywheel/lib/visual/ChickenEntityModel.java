package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelChicken;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelChicken}'s eight flat parts, in render order. {@code bill}/{@code chin}
 *  are slaved to {@code head} inside {@code setRotationAngles}, so copying all eight after posing suffices. */
public final class ChickenEntityModel implements EntityModel<ModelChicken> {
    private static final float MODEL_SCALE = 0.0625F;

    @Override
    public ModelChicken create() {
        return new ModelChicken();
    }

    @Override
    public ModelRenderer[] roots(ModelChicken m) {
        return new ModelRenderer[] { m.head, m.bill, m.chin, m.body, m.rightLeg, m.leftLeg, m.rightWing, m.leftWing };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelChicken m, int rootIndex) {
        // Per ModelChicken.render: head/bill/chin (roots 0-2) translate only (no scale); the rest is the 0.5 group.
        if (rootIndex <= 2) {
            dest.translate(0.0F, 5.0F * MODEL_SCALE, 2.0F * MODEL_SCALE);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
