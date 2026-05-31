package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelEnderMite;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelEnderMite}'s four body segments. Requires the access transformer
 *  on {@code ModelEnderMite.bodyParts}. */
public final class EndermiteEntityModel implements EntityModel<ModelEnderMite> {
    @Override
    public ModelEnderMite create() {
        return new ModelEnderMite();
    }

    @Override
    public ModelRenderer[] roots(ModelEnderMite m) {
        return m.bodyParts;
    }
}
