package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;

public interface TaggedVisualizationContexts {
    static VisualizationContext forEntity(VisualizationContext context, Entity entity) {
        return context instanceof TaggedVisualizationContexts tagged ? tagged.forEntity(entity) : context;
    }

    static VisualizationContext forBlockEntity(VisualizationContext context, TileEntity entity) {
        return context instanceof TaggedVisualizationContexts tagged ? tagged.forBlockEntity(entity) : context;
    }

    VisualizationContext forEntity(Entity entity);

    VisualizationContext forBlockEntity(TileEntity entity);
}
