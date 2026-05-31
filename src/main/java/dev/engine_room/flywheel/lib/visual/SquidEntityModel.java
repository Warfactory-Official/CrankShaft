package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSquid;

/** {@link EntityModel} for {@link ModelSquid}: the body plus its eight tentacles (render order — body
 *  first). Requires the access transformer on {@code ModelSquid.squidBody}/{@code squidTentacles}. */
public final class SquidEntityModel implements EntityModel<ModelSquid> {
    @Override
    public ModelSquid create() {
        return new ModelSquid();
    }

    @Override
    public ModelRenderer[] roots(ModelSquid m) {
        return EntityModel.prepend(m.squidBody, m.squidTentacles);
    }
}
