package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWither;

/** {@link EntityModel} for {@link ModelWither}: the three heads then the upper-body (spine) parts, in render draw
 *  order. {@code inflation} feeds the {@code ModelWither} constructor — 0 for the body, 0.5 for the additive aura
 *  overlay (same bone topology, so the aura copies the body's pose one-to-one). Requires the access transformer on
 *  {@code ModelWither.heads}/{@code upperBodyParts}. */
public final class WitherEntityModel implements EntityModel<ModelWither> {
    private final float inflation;

    public WitherEntityModel(float inflation) {
        this.inflation = inflation;
    }

    @Override
    public ModelWither create() {
        return new ModelWither(inflation);
    }

    @Override
    public ModelRenderer[] roots(ModelWither m) {
        return EntityModel.concat(m.heads, m.upperBodyParts);
    }
}
