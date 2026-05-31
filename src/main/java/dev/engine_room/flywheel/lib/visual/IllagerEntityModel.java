package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelIllager;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelIllager} (evoker, vindicator). Roots in render draw order: head, body,
 *  leg0, leg1, then the crossed-{@code arms} node and the two separate {@code rightArm}/{@code leftArm} — vanilla
 *  draws {@code arms} XOR the pair by {@code getArmPose()}, so the visual masks the inactive set per frame via
 *  {@code skipDraw}. The hat ({@code showModel=false}) and nose recurse as head children; all fields are public —
 *  no AT. */
public final class IllagerEntityModel implements EntityModel<ModelIllager> {
    @Override
    public ModelIllager create() {
        return new ModelIllager(0.0F, 0.0F, 64, 64);
    }

    @Override
    public ModelRenderer[] roots(ModelIllager m) {
        return new ModelRenderer[] { m.head, m.body, m.leg0, m.leg1, m.arms, m.rightArm, m.leftArm };
    }
}
