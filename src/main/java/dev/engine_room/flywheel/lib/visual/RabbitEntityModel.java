package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRabbit;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelRabbit}'s twelve parts, in render draw order. The adult model applies a
 *  0.6 scale + (0,1,0) translate inside {@code render()} — reproduce that in the visual's {@code preRenderCallback}
 *  (adults only; the baby branch replaces it with the per-group transforms below), not here. Requires the access
 *  transformer on all twelve {@code rabbit*} fields. */
public final class RabbitEntityModel implements EntityModel<ModelRabbit> {
    private static final float MODEL_SCALE = 0.0625F;

    @Override
    public ModelRabbit create() {
        return new ModelRabbit();
    }

    @Override
    public ModelRenderer[] roots(ModelRabbit m) {
        return new ModelRenderer[] {
                m.rabbitLeftFoot, m.rabbitRightFoot, m.rabbitLeftThigh, m.rabbitRightThigh,
                m.rabbitBody, m.rabbitLeftArm, m.rabbitRightArm, m.rabbitHead,
                m.rabbitRightEar, m.rabbitLeftEar, m.rabbitTail, m.rabbitNose,
        };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelRabbit m, int rootIndex) {
        // Per ModelRabbit.render (baby branch): head/ears/nose (roots 7, 8, 9, 11) at 0.56666666;
        // the rest (incl. the tail) at 0.4.
        if (rootIndex == 7 || rootIndex == 8 || rootIndex == 9 || rootIndex == 11) {
            dest.scale(0.56666666F).translate(0.0F, 22.0F * MODEL_SCALE, 2.0F * MODEL_SCALE);
        } else {
            dest.scale(0.4F).translate(0.0F, 36.0F * MODEL_SCALE, 0.0F);
        }
    }
}
