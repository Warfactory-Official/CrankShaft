package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.VillagerEntityModel;
import net.minecraft.client.model.ModelVillager;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

/** Villager — one baked model + the shared profession atlas, the per-instance UV region selecting the
 *  profession so all six professions batch into one instancer per bone. Profession is read each frame (it can
 *  change via cure). Modded professions (not in the atlas) hide the instanced visual and fall back to vanilla. */
public final class VillagerVisual extends AbstractLivingEntityVisual<EntityVillager, ModelVillager> {
    // Profession skins are registry-keyed (modded RLs), so the cell comes from the map — cached on
    // (atlas, skin) identity so the per-frame path is two reference compares, not a map probe.
    private VariantAtlas lastAtlas;
    private ResourceLocation lastSkin;
    private VariantAtlas.Cell cell;

    public VillagerVisual(VisualizationContext ctx, EntityVillager entity, float partialTick) {
        super(ctx, entity, partialTick, new VillagerEntityModel(), VillagerAtlas.material(), "villager", 0.5F);
    }

    @Override
    protected boolean shouldHide() {
        return !VillagerAtlas.isInstanceable(entity);
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityVillager entity) {
        VariantAtlas atlas = VillagerAtlas.atlas();
        ResourceLocation skin = entity.getProfessionForge().getSkin();
        if (atlas != lastAtlas || skin != lastSkin) {
            lastAtlas = atlas;
            lastSkin = skin;
            cell = atlas.cell(skin);
        }
        return cell;
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    // Vanilla RenderVillager scale; babies halve it uniformly (no per-root baby groups for villagers).
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(entity.isChild() ? 0.46875F : 0.9375F);
    }
}
