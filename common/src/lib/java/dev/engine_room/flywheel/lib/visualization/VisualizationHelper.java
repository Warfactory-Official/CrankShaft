package dev.engine_room.flywheel.lib.visualization;

import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

/**
 * Official entry point for resolving + queueing visuals, bound via {@link VisualizerRegistry}.
 */
public final class VisualizationHelper {
    private VisualizationHelper() {
    }

    public static void queueAdd(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }
        manager.effects()
               .queueAdd(effect);
    }

    public static void queueRemove(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }
        manager.effects()
               .queueRemove(effect);
    }

    /**
     * Call this when you want to run {@link Visual#update}.
     */
    public static void queueUpdate(BlockEntity blockEntity) {
        VisualizationManager manager = VisualizationManager.get(blockEntity.getLevel());
        if (manager == null) {
            return;
        }
        manager.blockEntities()
               .queueUpdate(blockEntity);
    }

    /**
     * Call this when you want to run {@link Visual#update}.
     */
    public static void queueUpdate(Entity entity) {
        VisualizationManager manager = VisualizationManager.get(entity.level());
        if (manager == null) {
            return;
        }
        manager.entities()
               .queueUpdate(entity);
    }

    /**
     * Call this when you want to run {@link Visual#update}.
     */
    public static void queueUpdate(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }
        manager.effects()
               .queueUpdate(effect);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends BlockEntity> BlockEntityVisualizer<? super T> getVisualizer(T blockEntity) {
        return VisualizerRegistry.getVisualizer((BlockEntityType<? super T>) blockEntity.getType());
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends Entity> EntityVisualizer<? super T> getVisualizer(T entity) {
        return VisualizerRegistry.getVisualizer((EntityType<? super T>) entity.getType());
    }

    public static <T extends BlockEntity> boolean canVisualize(T blockEntity) {
        return getVisualizer(blockEntity) != null;
    }

    public static <T extends Entity> boolean canVisualize(T entity) {
        return getVisualizer(entity) != null;
    }

    /**
     * Whether the given block entity is visualized and should not be rendered normally.
     */
    public static <T extends BlockEntity> boolean skipVanillaRender(T blockEntity) {
        BlockEntityVisualizer<? super T> visualizer = getVisualizer(blockEntity);
        return visualizer != null && visualizer.skipVanillaRender(blockEntity);
    }

    /**
     * Whether the given entity is visualized and should not be rendered normally.
     */
    public static <T extends Entity> boolean skipVanillaRender(T entity) {
        EntityVisualizer<? super T> visualizer = getVisualizer(entity);
        return visualizer != null && visualizer.skipVanillaRender(entity);
    }

    public static <T extends BlockEntity> boolean tryAddBlockEntity(T blockEntity) {
        Level level = blockEntity.getLevel();
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return false;
        }

        BlockEntityVisualizer<? super T> visualizer = getVisualizer(blockEntity);
        if (visualizer == null) {
            return false;
        }

        manager.blockEntities()
               .queueAdd(blockEntity);
        return visualizer.skipVanillaRender(blockEntity);
    }
}
