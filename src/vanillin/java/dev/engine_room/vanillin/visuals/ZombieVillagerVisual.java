package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.ArmorLayer;
import dev.engine_room.flywheel.lib.visual.BipedEntityModel;
import dev.engine_room.flywheel.lib.visual.HeldItemLayer;
import net.minecraft.client.model.ModelZombieVillager;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

/** Zombie villager — the {@code UV_TRANSFORMED} profession-atlas body (one draw/bone across all professions)
 *  plus the reused biped held-item + armor layers ({@code ModelZombieVillager} is a {@code ModelBiped}, nose
 *  baked into the head). Profession read per frame. Curing wobble/tint, custom-head items, modded
 *  professions, and invisibility fall back to vanilla via {@link ZombieVillagerAtlas#isInstanceable}. */
public final class ZombieVillagerVisual extends AbstractLivingEntityVisual<EntityZombieVillager, ModelZombieVillager> {
    // Profession skins are registry-keyed (modded RLs), so the cell comes from the map — cached on
    // (atlas, skin) identity so the per-frame path is two reference compares, not a map probe.
    private VariantAtlas lastAtlas;
    private ResourceLocation lastSkin;
    private VariantAtlas.Cell cell;

    public ZombieVillagerVisual(VisualizationContext ctx, EntityZombieVillager entity, float partialTick) {
        super(ctx, entity, partialTick, new BipedEntityModel<>(ModelZombieVillager::new),
                ZombieVillagerAtlas.material(), "zombie_villager", 0.5F);
        // bipedRightArm/bipedLeftArm are indices 2/3 in BipedEntityModel.roots; held items at bias 1, armor above at bias 2.
        addLayer(new HeldItemLayer(ctx, entity, instances, 2, 3, 1));
        addLayer(new ArmorLayer(ctx, entity, instances, 2));
    }

    @Override
    protected boolean shouldHide() {
        return !ZombieVillagerAtlas.isInstanceable(entity);
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    @Override
    protected void applyModelTransform(Matrix4f dest) {
        // ModelZombieVillager inherits ModelBiped.render's non-child sneak drop.
        if (entity.isSneaking() && !entity.isChild()) {
            dest.translate(0.0F, 0.2F, 0.0F);
        }
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityZombieVillager entity) {
        VariantAtlas atlas = ZombieVillagerAtlas.atlas();
        ResourceLocation skin = entity.getForgeProfession().getZombieSkin();
        if (atlas != lastAtlas || skin != lastSkin) {
            lastAtlas = atlas;
            lastSkin = skin;
            cell = atlas.cell(skin);
        }
        return cell;
    }
}
