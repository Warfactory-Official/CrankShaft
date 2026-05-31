package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSnowMan;

/** {@link EntityModel} for {@link ModelSnowMan}'s five flat parts, in render order. */
public final class SnowGolemEntityModel implements EntityModel<ModelSnowMan> {
    @Override
    public ModelSnowMan create() {
        return new ModelSnowMan();
    }

    @Override
    public ModelRenderer[] roots(ModelSnowMan m) {
        return new ModelRenderer[] { m.body, m.bottomBody, m.head, m.rightHand, m.leftHand };
    }
}
