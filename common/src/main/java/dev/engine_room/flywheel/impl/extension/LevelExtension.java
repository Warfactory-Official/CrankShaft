package dev.engine_room.flywheel.impl.extension;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Mixed into {@link Level} so callers can iterate every loaded entity, including ones {@code ClientLevel.entitiesForRendering()} omits.
 */
public interface LevelExtension {
    static Iterable<Entity> getAllLoadedEntities(Level level) {
        return ((LevelExtension) level).flw$getAllLoadedEntities();
    }

    Iterable<Entity> flw$getAllLoadedEntities();
}
