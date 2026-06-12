package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visual.*;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.util.LevelRenderer;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;

/**
 * The layer between a {@link BlockEntity} and the Flywheel backend.
 * <br>
 * <br> There are a few additional features that overriding classes can opt in to:
 * <ul>
 *     <li>{@link DynamicVisual}</li>
 *     <li>{@link TickableVisual}</li>
 *     <li>{@link LightUpdatedVisual}</li>
 *     <li>{@link ShaderLightVisual}</li>
 * </ul>
 * See the interfaces' documentation for more information about each one.
 *
 * <br> Implementing one or more of these will give an {@link AbstractBlockEntityVisual} access
 * to more interesting and regular points within a tick or a frame.
 *
 * @param <T> The type of {@link BlockEntity}.
 */
public abstract class AbstractBlockEntityVisual<T extends BlockEntity> extends AbstractVisual implements BlockEntityVisual<T>, LightUpdatedVisual {
    protected final T blockEntity;
    protected final BlockPos pos;
    protected final BlockPos visualPos;
    protected final BlockState blockState;
    protected SectionCollector lightSections;

    public AbstractBlockEntityVisual(VisualizationContext ctx, T blockEntity, float partialTick) {
        super(ctx, blockEntity.getLevel(), partialTick);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();
        this.blockState = blockEntity.getBlockState();
        this.visualPos = pos.subtract(ctx.renderOrigin());
    }

    /**
     * The level-space bounding box this visual occupies; drives which light sections it registers for relight.
     */
    protected AABB getRenderBoundingBox() {
        return new AABB(pos);
    }

    @Override
    public void setSectionCollector(SectionCollector sectionCollector) {
        this.lightSections = sectionCollector;

        AABB bb = getRenderBoundingBox();
        if (Double.isInfinite(bb.minX) || Double.isInfinite(bb.minY) || Double.isInfinite(bb.minZ)
                || Double.isInfinite(bb.maxX) || Double.isInfinite(bb.maxY) || Double.isInfinite(bb.maxZ)) {
            lightSections.sections(LongSet.of(SectionPos.asLong(pos)));
            return;
        }

        int minSx = SectionPos.blockToSectionCoord(Mth.floor(bb.minX));
        int minSy = SectionPos.blockToSectionCoord(Mth.floor(bb.minY));
        int minSz = SectionPos.blockToSectionCoord(Mth.floor(bb.minZ));
        int maxSx = SectionPos.blockToSectionCoord(Mth.ceil(bb.maxX) - 1);
        int maxSy = SectionPos.blockToSectionCoord(Mth.ceil(bb.maxY) - 1);
        int maxSz = SectionPos.blockToSectionCoord(Mth.ceil(bb.maxZ) - 1);

        int count = (maxSx - minSx + 1) * (maxSy - minSy + 1) * (maxSz - minSz + 1);
        LongSet sections = new LongArraySet(count);
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sy = minSy; sy <= maxSy; sy++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    sections.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        lightSections.sections(sections);
    }

    public BlockPos getVisualPosition() {
        return visualPos;
    }

    /**
     * @return {@code true} if this visual is possibly visible in the given frustum.
     */
    public boolean isVisible(FrustumIntersection frustum) {
        float x = visualPos.getX() + 0.5f;
        float y = visualPos.getY() + 0.5f;
        float z = visualPos.getZ() + 0.5f;
        return frustum.testSphere(x, y, z, MoreMath.SQRT_3_OVER_2);
    }

    /**
     * @return {@code true} if this visual shouldn't be updated this frame based on its distance from the camera.
     */
    public boolean doDistanceLimitThisFrame(DynamicVisual.Context context) {
        return !context.limiter()
                       .shouldUpdate(pos.distToCenterSqr(context.camera()
                                                                .position()));
    }

    protected int computePackedLight() {
        return LevelRenderer.getLightColor(level, pos);
    }

    protected void relight(BlockPos pos, @Nullable FlatLit... instances) {
        FlatLit.relight(LevelRenderer.getLightColor(level, pos), instances);
    }

    protected void relight(@Nullable FlatLit... instances) {
        relight(pos, instances);
    }

    protected void relight(BlockPos pos, Iterator<@Nullable FlatLit> instances) {
        FlatLit.relight(LevelRenderer.getLightColor(level, pos), instances);
    }

    protected void relight(Iterator<@Nullable FlatLit> instances) {
        relight(pos, instances);
    }

    protected void relight(BlockPos pos, Iterable<@Nullable FlatLit> instances) {
        FlatLit.relight(LevelRenderer.getLightColor(level, pos), instances);
    }

    protected void relight(Iterable<@Nullable FlatLit> instances) {
        relight(pos, instances);
    }
}
