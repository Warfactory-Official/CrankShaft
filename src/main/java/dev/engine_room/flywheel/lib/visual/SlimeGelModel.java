package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSlime;

/** {@link EntityModel} for the outer {@link ModelSlime}(0): a single translucent 8×8×8 gel cube, drawn over
 *  the inner {@code SlimeEntityModel} body. Requires the access transformer on {@code ModelSlime.slimeBodies}. */
public final class SlimeGelModel implements EntityModel<ModelSlime> {
    @Override
    public ModelSlime create() {
        return new ModelSlime(0);
    }

    @Override
    public ModelRenderer[] roots(ModelSlime m) {
        return new ModelRenderer[] { m.slimeBodies };
    }
}
