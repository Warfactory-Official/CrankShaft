package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.extension.LevelExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Level.class)
abstract class LevelMixin implements LevelExtension {
    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

    @Override
    public Iterable<Entity> flw$getAllLoadedEntities() {
        return getEntities().getAll();
    }
}
