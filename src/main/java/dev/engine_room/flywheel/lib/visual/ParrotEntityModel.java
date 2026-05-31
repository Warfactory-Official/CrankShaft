package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelParrot;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelParrot}, in render draw order. {@code head2}/{@code beak1}/{@code beak2}/
 *  {@code feather} are children of {@code head} and recurse. Requires the access transformer on the 7 root
 *  fields ({@code body}/{@code wingLeft}/{@code wingRight}/{@code tail}/{@code head}/{@code legLeft}/{@code legRight}). */
public final class ParrotEntityModel implements EntityModel<ModelParrot> {
    @Override
    public ModelParrot create() {
        return new ModelParrot();
    }

    @Override
    public ModelRenderer[] roots(ModelParrot m) {
        return new ModelRenderer[] { m.body, m.wingLeft, m.wingRight, m.tail, m.head, m.legLeft, m.legRight };
    }
}
