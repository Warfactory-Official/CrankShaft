package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.LightUpdatedVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.backend.engine.SectionPos;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.util.LevelRenderer;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;

/**
 * The layer between a {@link TileEntity} and the Flywheel backend.
 * <br>
 * <br> There are a few additional features that overriding classes can opt in to:
 * <ul>
 *     <li>{@link DynamicVisual}</li>
 *     <li>{@link dev.engine_room.flywheel.api.visual.TickableVisual}</li>
 *     <li>{@link LightUpdatedVisual}</li>
 *     <li>{@link dev.engine_room.flywheel.api.visual.ShaderLightVisual}</li>
 * </ul>
 * See the interfaces' documentation for more information about each one.
 *
 * <br> Implementing one or more of these will give an {@link AbstractBlockEntityVisual} access
 * to more interesting and regular points within a tick or a frame.
 *
 * @param <T> The type of {@link TileEntity}.
 */
public abstract class AbstractBlockEntityVisual<T extends TileEntity> extends AbstractVisual implements BlockEntityVisual<T>, LightUpdatedVisual {
    protected final T blockEntity;
    protected final BlockPos pos;
    protected final BlockPos visualPos;
    protected final IBlockState blockState;
    protected SectionCollector lightSections;

    public AbstractBlockEntityVisual(VisualizationContext ctx, T blockEntity, float partialTick) {
        super(ctx, blockEntity.getWorld(), partialTick);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getPos();
        this.blockState = blockEntity.getWorld().getBlockState(pos);
        this.visualPos = pos.subtract(ctx.renderOrigin());
    }

    @Override
    public void setSectionCollector(SectionCollector sectionCollector) {
        this.lightSections = sectionCollector;
        // Multi-section support: visuals can span more than one 16x16x16 section. Use
        // TileEntity.getRenderBoundingBox (the 1.12.2 canonical extent hint) to enumerate all
        // intersecting sections so light updates outside the BE's own section still fire.
        AxisAlignedBB bb = blockEntity.getRenderBoundingBox();
        if (bb == TileEntity.INFINITE_EXTENT_AABB) {
            LongSet section = new LongArraySet(1);
            section.add(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            lightSections.sections(section);
            return;
        }
        int minSx = MathHelper.floor(bb.minX) >> 4;
        int minSy = MathHelper.floor(bb.minY) >> 4;
        int minSz = MathHelper.floor(bb.minZ) >> 4;
        int maxSx = (MathHelper.ceil(bb.maxX) - 1) >> 4;
        int maxSy = (MathHelper.ceil(bb.maxY) - 1) >> 4;
        int maxSz = (MathHelper.ceil(bb.maxZ) - 1) >> 4;
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

    /**
     * In order to accommodate for floating point precision errors at high coordinates,
     * {@link dev.engine_room.flywheel.api.visualization.VisualManager}s are allowed to arbitrarily adjust the origin, and
     * shift the level matrix provided as a shader uniform accordingly.
     *
     * @return The {@link BlockPos position} of the {@link TileEntity} this visual
     * represents should be rendered at to appear in the correct location.
     */
    public BlockPos getVisualPosition() {
        return visualPos;
    }

    /**
     * Check if this visual is within the given frustum.
     * @param frustum The current frustum.
     * @return {@code true} if this visual is possibly visible.
     */
    public boolean isVisible(FrustumIntersection frustum) {
        float x = visualPos.getX() + 0.5f;
        float y = visualPos.getY() + 0.5f;
        float z = visualPos.getZ() + 0.5f;
        return frustum.testSphere(x, y, z, MoreMath.SQRT_3_OVER_2);
    }

    /**
     * Limits which frames this visual is updated on based on its distance from the camera.
     * <p>
     * You may optionally do this check to avoid updating your visual every frame when it is far away.
     *
     * @param context The current frame context.
     * @return {@code true} if this visual shouldn't be updated this frame based on its distance from the camera.
     */
    public boolean doDistanceLimitThisFrame(DynamicVisual.Context context) {
        Vec3d cam = context.camera().getPosition();
        double dx = (pos.getX() + 0.5) - cam.x;
        double dy = (pos.getY() + 0.5) - cam.y;
        double dz = (pos.getZ() + 0.5) - cam.z;
        double distSqr = dx * dx + dy * dy + dz * dz;
        return !context.limiter().shouldUpdate(distSqr);
    }

    protected int computePackedLight() {
        return LevelRenderer.getLightColor(level, blockState, pos);
    }

    protected int computePackedLight(BlockPos at) {
        return LevelRenderer.getLightColor(level, at);
    }

    protected void relight(BlockPos pos, @Nullable FlatLit... instances) {
        FlatLit.relight(computePackedLight(pos), instances);
    }

    protected void relight(@Nullable FlatLit... instances) {
        FlatLit.relight(computePackedLight(), instances);
    }

    protected void relight(BlockPos pos, Iterator<@Nullable FlatLit> instances) {
        FlatLit.relight(computePackedLight(pos), instances);
    }

    protected void relight(Iterator<@Nullable FlatLit> instances) {
        FlatLit.relight(computePackedLight(), instances);
    }

    protected void relight(BlockPos pos, Iterable<@Nullable FlatLit> instances) {
        FlatLit.relight(computePackedLight(pos), instances);
    }

    protected void relight(Iterable<@Nullable FlatLit> instances) {
        FlatLit.relight(computePackedLight(), instances);
    }
}
