package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelBat;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelBat}: head and body (the four wing boxes are children of the body
 *  and recurse). Requires the access transformer on {@code ModelBat.batHead}/{@code batBody}. */
public final class BatEntityModel implements EntityModel<ModelBat> {
    @Override
    public ModelBat create() {
        return new ModelBat();
    }

    @Override
    public ModelRenderer[] roots(ModelBat m) {
        return new ModelRenderer[] { m.batHead, m.batBody };
    }
}
