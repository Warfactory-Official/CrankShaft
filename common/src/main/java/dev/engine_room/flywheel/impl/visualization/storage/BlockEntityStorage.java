package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.queues.MpscUnboundedXaddArrayQueue;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.Queue;

public class BlockEntityStorage extends Storage<BlockEntity> {
    private final Long2ObjectMap<BlockEntityVisual<?>> posLookup = new Long2ObjectOpenHashMap<>();

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
        var visual = visualizer.createVisual(visualizationContext, obj, partialTick);

        long key = obj.getBlockPos().asLong();
        synchronized (posLookup) {
            posLookup.put(key, visual);
        }

        return visual;
    }

    @Override
    public void remove(BlockEntity obj) {
        posLookup.remove(obj.getBlockPos().asLong());
        super.remove(obj);
    }

    @Override
    public Plan<RenderContext> recreateAllPlan(VisualizationContext visualizationContext) {
        return SimplePlan.<RenderContext>of(ctx -> {
                             synchronized (posLookup) {
                                 posLookup.clear();
                             }
                         })
                         .then(super.recreateAllPlan(visualizationContext));
    }

    @Override
    public void invalidate() {
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
