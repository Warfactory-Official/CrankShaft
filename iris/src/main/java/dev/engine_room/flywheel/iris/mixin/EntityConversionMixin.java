package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A guest draw tag is fixed when the visual is created (instancers are keyed by {@code TaggedEnvironment}, and every
 * instance in one shares its draws), so the one Iris entity id that changes mid-life can only follow by recreating
 * the visual. {@code ZombieVillager} does not override the hook, hence the {@link Entity} target.
 */
@Mixin(Entity.class)
abstract class EntityConversionMixin {
    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("RETURN"))
    private void flywheel$retagConvertingVillager(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        if (!((Object) this instanceof ZombieVillager villager)
                || !WorldRenderingSettings.INSTANCE.hasVillagerConversionId()
                || !accessor.equals(ZombieVillagerAccessor.flywheel$convertingId())) {
            return;
        }
        VisualizationManager manager = VisualizationManager.get(villager.level());
        if (manager == null) {
            return;
        }
        manager.entities()
               .queueRemove(villager);
        manager.entities()
               .queueAdd(villager);
    }
}
