package dev.engine_room.flywheel.iris.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ZombieVillager.class)
public interface ZombieVillagerAccessor {
    @Accessor("DATA_CONVERTING_ID")
    static EntityDataAccessor<Boolean> flywheel$convertingId() {
        throw new AssertionError();
    }
}
