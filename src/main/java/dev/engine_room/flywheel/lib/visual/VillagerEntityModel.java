package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelVillager;

/** {@link EntityModel} for {@link ModelVillager}; the nose is a child of the head and recurses (not in roots). */
public final class VillagerEntityModel implements EntityModel<ModelVillager> {
    @Override
    public ModelVillager create() {
        return new ModelVillager(0.0F);
    }

    @Override
    public ModelRenderer[] roots(ModelVillager m) {
        return new ModelRenderer[] {
                m.villagerHead, m.villagerBody, m.rightVillagerLeg, m.leftVillagerLeg, m.villagerArms,
        };
    }

    // Villager baby scaling is uniform (RenderVillager.preRenderCallback halves the root scale) and lives in
    // the visual's preRenderCallback; the per-root transform is identity, so the default babyTransform stands.
    @Override
    public boolean hasBabyTransform() {
        return true;
    }
}
