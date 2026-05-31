package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelGuardian;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelGuardian}: a single {@code guardianBody} root — the eye, twelve
 *  spines, and three-segment tail are all children and recurse. Requires the access transformer on
 *  {@code ModelGuardian.guardianBody}. */
public final class GuardianEntityModel implements EntityModel<ModelGuardian> {
    @Override
    public ModelGuardian create() {
        return new ModelGuardian();
    }

    @Override
    public ModelRenderer[] roots(ModelGuardian m) {
        return new ModelRenderer[] { m.guardianBody };
    }
}
