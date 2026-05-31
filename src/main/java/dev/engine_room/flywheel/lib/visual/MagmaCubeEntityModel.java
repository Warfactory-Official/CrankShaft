package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelMagmaCube;
import net.minecraft.client.model.ModelRenderer;

/**
 * {@link EntityModel} for {@link ModelMagmaCube}: the core cube plus its eight squish segments
 * (render order — core first, then segments). The per-segment Y offsets come from the model's
 * {@code setLivingAnimations} squish spread, which {@link AbstractLivingEntityVisual} runs each frame.
 * Requires the access transformer on {@code ModelMagmaCube.core}/{@code segments}.
 */
public final class MagmaCubeEntityModel implements EntityModel<ModelMagmaCube> {
    @Override
    public ModelMagmaCube create() {
        return new ModelMagmaCube();
    }

    @Override
    public ModelRenderer[] roots(ModelMagmaCube m) {
        return EntityModel.prepend(m.core, m.segments);
    }
}
