package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelOcelot;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelOcelot}'s eight parts, in render draw order. Requires the access
 *  transformer on all eight {@code ocelot*} fields. */
public final class OcelotEntityModel implements EntityModel<ModelOcelot> {
    private static final float MODEL_SCALE = 0.0625F;

    @Override
    public ModelOcelot create() {
        return new ModelOcelot();
    }

    @Override
    public ModelRenderer[] roots(ModelOcelot m) {
        return new ModelRenderer[] {
                m.ocelotHead, m.ocelotBody, m.ocelotTail, m.ocelotTail2,
                m.ocelotBackLeftLeg, m.ocelotBackRightLeg, m.ocelotFrontLeftLeg, m.ocelotFrontRightLeg,
        };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelOcelot m, int rootIndex) {
        // Per ModelOcelot.render: head (root 0) at 0.75; the rest is the 0.5 group.
        if (rootIndex == 0) {
            dest.scale(0.75F).translate(0.0F, 10.0F * MODEL_SCALE, 4.0F * MODEL_SCALE);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
