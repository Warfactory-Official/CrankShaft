package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSilverfish;

/** {@link EntityModel} for {@link ModelSilverfish}: seven body segments plus three dust-sprite "wings"
 *  (render order — body parts first). Requires the access transformer on
 *  {@code ModelSilverfish.silverfishBodyParts}/{@code silverfishWings}. */
public final class SilverfishEntityModel implements EntityModel<ModelSilverfish> {
    @Override
    public ModelSilverfish create() {
        return new ModelSilverfish();
    }

    @Override
    public ModelRenderer[] roots(ModelSilverfish m) {
        return EntityModel.concat(m.silverfishBodyParts, m.silverfishWings);
    }
}
