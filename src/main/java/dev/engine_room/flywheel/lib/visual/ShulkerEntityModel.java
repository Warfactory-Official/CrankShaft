package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelShulker;

public final class ShulkerEntityModel implements EntityModel<ModelShulker> {
    @Override
    public ModelShulker create() {
        return new ModelShulker();
    }

    @Override
    public ModelRenderer[] roots(ModelShulker m) {
        return new ModelRenderer[] { m.base, m.lid };
    }
}
