package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelShulker;

/** {@link EntityModel} for {@link ModelShulker}'s head turret alone (the open shulker's inner cube), baked as its
 *  own single-root tree because the head is a separate vanilla layer with a per-face transform. {@code head} is
 *  public — no AT. */
public final class ShulkerHeadEntityModel implements EntityModel<ModelShulker> {
    @Override
    public ModelShulker create() {
        return new ModelShulker();
    }

    @Override
    public ModelRenderer[] roots(ModelShulker m) {
        return new ModelRenderer[] { m.head };
    }
}
