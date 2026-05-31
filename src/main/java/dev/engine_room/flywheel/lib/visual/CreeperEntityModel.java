package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelCreeper;
import net.minecraft.client.model.ModelRenderer;

/** Charged-creeper aura is a separate inflated copy built via the {@code inflation} ctor (see {@code CreeperChargeLayer}). */
public final class CreeperEntityModel implements EntityModel<ModelCreeper> {
    private final float inflation;

    public CreeperEntityModel() {
        this(0.0F);
    }

    public CreeperEntityModel(float inflation) {
        this.inflation = inflation;
    }

    @Override
    public ModelCreeper create() {
        return new ModelCreeper(inflation);
    }

    @Override
    public ModelRenderer[] roots(ModelCreeper m) {
        return new ModelRenderer[] { m.head, m.body, m.leg1, m.leg2, m.leg3, m.leg4 };
    }
}
