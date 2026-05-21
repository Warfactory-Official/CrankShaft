package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.extension.TileEntityExtension;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

public class BlockEntityStorage extends Storage<TileEntity> {
    private final Long2ObjectMap<BlockEntityVisual<?>> posLookup = new Long2ObjectOpenHashMap<>();

    // 1.12.2: unsync read; safe only after the frame barrier (syncUntil(frameFlag)) has fenced
    // the synchronized writes in createRaw. Don't call from a worker.
    @Nullable
    public BlockEntityVisual<?> visualAtPos(long pos) {
        return posLookup.get(pos);
    }

    @Override
    public boolean willAccept(TileEntity blockEntity) {
        if (blockEntity.isInvalid()) {
            return false;
        }

        if (!((TileEntityExtension) blockEntity).flw$canVisualize()) {
            return false;
        }

        World level = blockEntity.getWorld();
        if (level == null) {
            return false;
        }

        BlockPos pos = blockEntity.getPos();
        if (level.isAirBlock(pos)) {
            return false;
        }

        return level.isBlockLoaded(pos);
    }

    @Override
    @Nullable
    protected BlockEntityVisual<?> createRaw(VisualizationContext visualizationContext, TileEntity obj, float partialTick) {
        var visual = ((TileEntityExtension) obj).flw$createVisual(visualizationContext, partialTick);
        if (visual == null) {
            return null;
        }

        // 1.12.2: workers call createRaw concurrently during Storage.recreateAllPlan;
        // Long2ObjectOpenHashMap is not thread-safe.
        long key = obj.getPos().toLong();
        synchronized (posLookup) {
            posLookup.put(key, visual);
        }

        return visual;
    }

    @Override
    public void remove(TileEntity obj) {
        // 1.12.2: reached only from the single-threaded processQueue drain, which the frame
        // barrier separates from createRaw's worker phase — so the unsync mutation cannot race.
        posLookup.remove(obj.getPos().toLong());
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
}
