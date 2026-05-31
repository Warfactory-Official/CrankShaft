package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelVex;

/** {@link EntityModel} for {@link ModelVex}: the visible biped parts plus the two wings. A vex sets
 *  {@code bipedLeftLeg} and {@code bipedHeadwear} to {@code showModel=false}, so both are omitted here —
 *  the baker ignores {@code showModel} and would otherwise draw them. Requires the access transformer on
 *  {@code ModelVex.rightWing}/{@code leftWing}. */
public final class VexEntityModel implements EntityModel<ModelVex> {
    @Override
    public ModelVex create() {
        return new ModelVex();
    }

    @Override
    public ModelRenderer[] roots(ModelVex m) {
        return new ModelRenderer[] {
                m.bipedHead, m.bipedBody, m.bipedRightArm, m.bipedLeftArm, m.bipedRightLeg,
                m.rightWing, m.leftWing,
        };
    }
}
