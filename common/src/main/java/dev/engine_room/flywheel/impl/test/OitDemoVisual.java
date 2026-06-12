package dev.engine_room.flywheel.impl.test;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

public final class OitDemoVisual extends AbstractBlockEntityVisual<TileEntityOitDemo>
        implements ShaderLightVisual, SimpleDynamicVisual {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final BlockState renderedState;
    private TransformedInstance instance;
    private int cullMask;
    private int packedLight;

    public OitDemoVisual(VisualizationContext ctx, TileEntityOitDemo blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        renderedState = resolveRenderedState(blockState.getBlock(), blockState);
        cullMask = computeCullMask();
        packedLight = computePackedLight();
        instance = createInstance();
        writePose();
    }

    private static BlockState effectiveGlassState(BlockState state) {
        if (state.getBlock() instanceof BlockOitDemoGlass) {
            return resolveRenderedState(state.getBlock(), state);
        }
        return state;
    }

    private static BlockState resolveRenderedState(Block markerBlock, BlockState markerState) {
        if (markerBlock instanceof BlockOitDemoStainedGlass) {
            DyeColor color = markerState.getValue(BlockOitDemoStained.COLOR);
            return Blocks.STAINED_GLASS.pick(color)
                                       .defaultBlockState();
        }
        if (markerBlock instanceof BlockOitDemoStainedGlassPane) {
            DyeColor color = markerState.getValue(BlockOitDemoStained.COLOR);
            return Blocks.STAINED_GLASS_PANE.pick(color)
                                            .defaultBlockState();
        }
        if (markerBlock instanceof BlockOitDemoGlassPane) {
            return Blocks.GLASS_PANE.defaultBlockState();
        }
        return Blocks.GLASS.defaultBlockState();
    }

    private TransformedInstance createInstance() {
        TransformedInstance i = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.block(renderedState, cullMask))
                .createInstance();
        i.overlay(OverlayTexture.NO_OVERLAY);
        return i;
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        int newMask = computeCullMask();
        if (newMask == cullMask) {
            return;
        }
        cullMask = newMask;
        instance.delete();
        instance = createInstance();
        writePose();
    }

    private int computeCullMask() {
        int mask = 0;
        for (Direction dir : DIRECTIONS) {
            BlockState neighbour = effectiveGlassState(level.getBlockState(pos.relative(dir)));
            if (!Block.shouldRenderFace(renderedState, neighbour, dir)) {
                mask |= 1 << dir.ordinal();
            }
        }
        return mask;
    }

    private void writePose() {
        instance.setTransform(new Matrix4f().translate(visualPos.getX(), visualPos.getY(), visualPos.getZ()));
        instance.light(packedLight);
        instance.setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        packedLight = computePackedLight();
        instance.light(packedLight);
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        consumer.accept(instance);
    }

    @Override
    protected AABB getRenderBoundingBox() {
        // The SMOOTH light shader trilinearly samples a 2x2x2 corner, which can reach into neighbour sections.
        return new AABB(pos).inflate(1.0);
    }
}
