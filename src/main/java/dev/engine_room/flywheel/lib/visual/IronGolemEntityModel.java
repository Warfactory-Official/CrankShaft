package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelIronGolem;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelIronGolem}'s six flat parts, in render order. The attack swing and
 *  rose-holding arm pose come from {@code setLivingAnimations}, which {@link AbstractLivingEntityVisual}
 *  runs each frame. */
public final class IronGolemEntityModel implements EntityModel<ModelIronGolem> {
    @Override
    public ModelIronGolem create() {
        return new ModelIronGolem();
    }

    @Override
    public ModelRenderer[] roots(ModelIronGolem m) {
        return new ModelRenderer[] {
                m.ironGolemHead, m.ironGolemBody, m.ironGolemLeftLeg, m.ironGolemRightLeg,
                m.ironGolemRightArm, m.ironGolemLeftArm,
        };
    }
}
