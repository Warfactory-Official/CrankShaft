package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.backend.engine.TaggedVisualizationContexts;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.lib.queues.MpscUnboundedXaddArrayQueue;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.Queue;
import java.util.function.Supplier;

public class BlockEntityStorage extends Storage<BlockEntity> {
    private final Long2ObjectMap<BlockEntityVisual<?>> posLookup = new Long2ObjectOpenHashMap<>();
    private @Nullable FarVisuals farVisuals;
    private @Nullable Supplier<Vec3i> renderOrigin;

    @Nullable
    public BlockEntityVisual<?> visualAtPos(long pos) {
        return posLookup.get(pos);
    }

    @Override
    public boolean willAccept(BlockEntity blockEntity) {
        if (blockEntity.isRemoved()) {
            return false;
        }

        if (!VisualizationHelper.canVisualize(blockEntity)) {
            return false;
        }

        Level level = blockEntity.getLevel();
        if (level == null) {
            return false;
        }

        BlockPos pos = blockEntity.getBlockPos();
        if (level.getBlockState(pos).isAir()) {
            return false;
        }

        return level.isLoaded(pos);
    }

    @Override
    @Nullable
    protected BlockEntityVisual<?> createRaw(VisualizationContext visualizationContext, BlockEntity obj,
                                             float partialTick) {
        var visualizer = VisualizationHelper.getVisualizer(obj);
        if (visualizer == null) {
            return null;
        }
        if (visualizationContext instanceof TaggedVisualizationContexts tagged) {
            visualizationContext = tagged.forBlockEntity(obj);
        }
        var visual = visualizer.createVisual(visualizationContext, obj, partialTick);
        if (farVisuals != null) {
            farVisuals.visualized(obj.getBlockPos());
        }

        long key = obj.getBlockPos().asLong();
        synchronized (posLookup) {
            posLookup.put(key, visual);
        }

        return visual;
    }

    public void farVisuals(FarVisuals farVisuals, Supplier<Vec3i> renderOrigin) {
        this.farVisuals = farVisuals;
        this.renderOrigin = renderOrigin;
    }

    @Override
    public void remove(BlockEntity obj) {
        BlockPos pos = obj.getBlockPos();
        posLookup.remove(pos.asLong());
        // Breaks and state changes leave the chunk loaded: nothing to keep, or a new visual follows.
        Level level = obj.getLevel();
        if (farVisuals != null) {
            int light = farVisuals.droppedLight(pos.asLong());
            Visual visual = visuals.get(obj);
            if (visual != null && level != null && !level.isLoaded(pos)
                    && FarVisuals.keeps(visual, obj.getBlockState())) {
                farVisuals.capture(pos, obj.getBlockState(), visual, renderOrigin.get(), light);
            }
        }
        super.remove(obj);
    }

    @Override
    public Plan<RenderContext> recreateAllPlan(VisualizationContext visualizationContext) {
        return SimplePlan.<RenderContext>of(_ -> {
                             synchronized (posLookup) {
                                 posLookup.clear();
                             }
                         })
                         .then(super.recreateAllPlan(visualizationContext));
    }

    @Override
    public void invalidate() {
        // Visualization resets before a render distance change drops chunks, and recreates only what sections
        // rebuild: keep what every visual leaves behind; a recreated one ends its entry.
        if (farVisuals != null) {
            Vec3i origin = renderOrigin.get();
            visuals.forEach((blockEntity, visual) -> {
                if (FarVisuals.keeps(visual, blockEntity.getBlockState())) {
                    farVisuals.capture(blockEntity.getBlockPos(), blockEntity.getBlockState(), visual, origin,
                            farVisuals.light(blockEntity.getBlockPos()));
                }
            });
        }
        posLookup.clear();
        super.invalidate();
    }

    // Block entities have MULTIPLE concurrent producers (section-compiler workers +
    // the main thread); the default SPSC queue silently drops offers under contention.
    @Override
    public Queue<Transaction<BlockEntity>> getTransactionQueue() {
        return new MpscUnboundedXaddArrayQueue<>(1024);
    }
}
