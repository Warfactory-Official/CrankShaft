package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWitch;

public final class WitchEntityModel implements EntityModel<ModelWitch> {
    @Override
    public ModelWitch create() {
        return new ModelWitch(0.0F);
    }

    @Override
    public ModelRenderer[] roots(ModelWitch m) {
        return new ModelRenderer[] {
                m.villagerHead, m.villagerBody, m.rightVillagerLeg, m.leftVillagerLeg, m.villagerArms,
        };
    }
}
