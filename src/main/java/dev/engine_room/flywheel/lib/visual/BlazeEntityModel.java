package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelBlaze;
import net.minecraft.client.model.ModelRenderer;

/** {@link EntityModel} for {@link ModelBlaze}: the head plus its twelve orbiting sticks (render order —
 *  head first). The sticks' per-frame rotation points come from {@code setRotationAngles}. Requires the
 *  access transformer on {@code ModelBlaze.blazeHead}/{@code blazeSticks}. */
public final class BlazeEntityModel implements EntityModel<ModelBlaze> {
    @Override
    public ModelBlaze create() {
        return new ModelBlaze();
    }

    @Override
    public ModelRenderer[] roots(ModelBlaze m) {
        return EntityModel.prepend(m.blazeHead, m.blazeSticks);
    }
}
