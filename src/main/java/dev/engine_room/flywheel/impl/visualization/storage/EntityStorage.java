package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.extension.EntityExtension;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

public class EntityStorage extends Storage<Entity> {
    @Override
    protected EntityVisual<?> createRaw(VisualizationContext context, Entity obj, float partialTick) {
        // Null when no visualizer is registered/enabled, matching the old getVisualizer-null guard.
        return ((EntityExtension) obj).flw$createVisual(context, partialTick);
    }

    @Override
    public boolean willAccept(Entity entity) {
        if (entity.isDead) {
            return false;
        }

        if (!((EntityExtension) entity).flw$canVisualize()) {
            return false;
        }

        World level = entity.world;
        return level != null;
    }
}
