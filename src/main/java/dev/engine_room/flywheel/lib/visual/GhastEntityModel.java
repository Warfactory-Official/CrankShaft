package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelGhast;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelGhast}: the body plus nine tentacles (render order — body first). The
 *  {@code render()}-local {@code translate(0,0.6,0)} is reproduced in the visual's {@code preRenderCallback}.
 *  Requires the access transformer on {@code ModelGhast.body}/{@code tentacles}. */
public final class GhastEntityModel implements EntityModel<ModelGhast> {
    @Override
    public ModelGhast create() {
        return new ModelGhast();
    }

    @Override
    public ModelRenderer[] roots(ModelGhast m) {
        return EntityModel.prepend(m.body, m.tentacles);
    }
}
