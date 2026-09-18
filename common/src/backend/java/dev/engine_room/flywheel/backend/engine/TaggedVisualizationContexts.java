package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Implemented by {@link EngineImpl}'s visualization context: the context a visual of the given object is created
 * with, per {@link EngineImpl#drawTag}. Storages call it on visual-creation worker threads.
 */
public interface TaggedVisualizationContexts {
    VisualizationContext forBlockEntity(BlockEntity blockEntity);

    VisualizationContext forEntity(Entity entity);
}
