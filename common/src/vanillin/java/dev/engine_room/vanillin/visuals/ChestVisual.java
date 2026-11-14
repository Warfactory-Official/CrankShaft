package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.FarVisual;
import dev.engine_room.flywheel.api.visual.FarVisualState;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.util.LevelRenderer;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.FarVisualStates;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import it.unimi.dsi.fastutil.floats.Float2FloatFunction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class ChestVisual<T extends BlockEntity & LidBlockEntity> extends AbstractBlockEntityVisual<T> implements SimpleDynamicVisual, FarVisual {
    @Nullable
    private final ModelTree model;
    @Nullable
    private final InstanceTree instances;
    @Nullable
    private final InstanceTree lid;
    @Nullable
    private final InstanceTree lock;

    @Nullable
    private final Matrix4fc initialPose;
    private final BrightnessCombiner brightnessCombiner = new BrightnessCombiner();
    private final DoubleBlockCombiner.@Nullable NeighborCombineResult<? extends ChestBlockEntity> neighborCombineResult;
    @Nullable
    private final Float2FloatFunction lidProgress;

    private float lastProgress = Float.NaN;
    private int packedLight;

    public ChestVisual(VisualizationContext ctx, T blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        Block block = blockState.getBlock();
        if (block instanceof AbstractChestBlock<?> chestBlock) {
            model = ContainerVisualModels.chestModel(blockState, blockEntity instanceof EnderChestBlockEntity,
                    blockEntity instanceof TrappedChestBlockEntity);
            instances = InstanceTree.create(instancerProvider(), model);
            lid = instances.childOrThrow("lid");
            lock = instances.childOrThrow("lock");

            initialPose = ContainerVisualModels.chestPose(blockState, getVisualPosition());
            neighborCombineResult = chestBlock.combine(blockState, level, pos, true);
            lidProgress = neighborCombineResult.apply(ChestBlock.opennessCombiner(blockEntity));

            lastProgress = lidProgress.get(partialTick);
            applyLidTransform(lastProgress);
        } else {
            model = null;
            instances = null;
            lid = null;
            lock = null;
            initialPose = null;
            neighborCombineResult = null;
            lidProgress = null;
        }
    }


    @Override
    protected AABB getRenderBoundingBox() {
        return AABB.encapsulatingFullBlocks(pos.offset(-1, 0, -1), pos.offset(1, 1, 1));
    }

    @Override
    public void beginFrame(Context context) {
        if (instances == null) {
            return;
        }

        if (doDistanceLimitThisFrame(context) || !isVisible(context.frustum())) {
            return;
        }

        float progress = lidProgress.get(context.partialTick());
        if (lastProgress == progress) {
            return;
        }
        lastProgress = progress;

        applyLidTransform(progress);
    }

    private void applyLidTransform(float progress) {
        progress = 1.0F - progress;
        progress = 1.0F - progress * progress * progress;

        lid.xRot(-(progress * ((float) Math.PI / 2F)));
        lock.xRot(lid.xRot());
        instances.updateInstancesStatic(initialPose);
    }

    @Override
    public void updateLight(float _) {
        if (instances != null) {
            packedLight = neighborCombineResult.apply(brightnessCombiner);
            instances.traverse(instance -> {
                instance.light(packedLight)
                        .setChanged();
            });
        }
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        if (instances != null) {
            instances.traverse(consumer);
        }
    }

    @Override
    public FarVisualState retainedState() {
        if (model == null) {
            return new FarVisualState(List.of());
        }
        return FarVisualStates.modelTree(visualizationContext, model, initialPose,
                FarVisualState.Appearance.lit(packedLight));
    }

    @Override
    protected void _delete() {
        if (instances != null) {
            instances.delete();
        }
    }

    private class BrightnessCombiner implements DoubleBlockCombiner.Combiner<BlockEntity, Integer> {
        @Override
        public Integer acceptDouble(BlockEntity first, BlockEntity second) {
            int firstLight = LevelRenderer.getLightColor(first.getLevel(), first.getBlockPos());
            int secondLight = LevelRenderer.getLightColor(second.getLevel(), second.getBlockPos());
            int firstBlockLight = LightCoordsUtil.block(firstLight);
            int secondBlockLight = LightCoordsUtil.block(secondLight);
            int firstSkyLight = LightCoordsUtil.sky(firstLight);
            int secondSkyLight = LightCoordsUtil.sky(secondLight);
            return LightCoordsUtil.pack(Math.max(firstBlockLight, secondBlockLight),
                    Math.max(firstSkyLight, secondSkyLight));
        }

        @Override
        public Integer acceptSingle(BlockEntity single) {
            return LevelRenderer.getLightColor(single.getLevel(), single.getBlockPos());
        }

        @Override
        public Integer acceptNone() {
            return LevelRenderer.getLightColor(level, pos);
        }
    }
}
