package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.tileentity.TileEntity;
import org.joml.Matrix4f;

import java.util.function.Consumer;

/**
 * Shared base for chest-shaped block entities. Upstream's {@code ChestVisual} is generic over
 * {@code BlockEntity & LidBlockEntity} (1.17+ interface); 1.12.2 has no such interface, so the
 * abstraction lives here. Subclasses fill in model/material/animation specifics.
 */
public abstract class AbstractChestVisual<T extends TileEntity>
        extends AbstractBlockEntityVisual<T>
        implements SimpleDynamicVisual {

    // Subclass-mutable so ChestVisual's pair-state-change path can rebuild instances+poses
    // without recreating the whole visual. Single-state subclasses (EnderChest) just set
    // these once in their constructor and leave them alone.
    protected InstanceTree instances;
    protected InstanceTree lid;
    protected InstanceTree knob;
    protected Matrix4f initialPose;
    protected int packedLight;
    protected float lastLidAngle = Float.NaN;

    protected AbstractChestVisual(VisualizationContext ctx, T te, float partialTick) {
        super(ctx, te, partialTick);
    }

    // Overridable: ChestVisual checks pair-state-change here before delegating to writePose.
    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (doDistanceLimitThisFrame(ctx)) {
            return;
        }
        writePose(ctx.partialTick());
    }

    protected void writePose(float partialTick) {
        float lidAngle = computeLidAngle(partialTick);
        if (lidAngle == lastLidAngle) {
            return;
        }
        lastLidAngle = lidAngle;
        // Vanilla ModelChest.renderAll() copies chestLid.rotateAngleX onto chestKnob, so the
        // knob rotates with the lid in both TileEntityChestRenderer and TileEntityEnderChestRenderer.
        lid.xRot(lidAngle);
        knob.xRot(lidAngle);
        instances.updateInstancesStatic(initialPose);
    }

    /** Compute the eased lid angle for the current frame. Subclasses use {@link #ease} after
     *  collecting their raw input(s) — chest takes max with adjacent halves, ender chest is
     *  single-source. Returns the angle in radians, already negated for X-axis rotation. */
    protected abstract float computeLidAngle(float partialTick);

    /** Vanilla's lid-angle cubic ease: f' = 1 - (1-f)³, mapped to [-π/2, 0]. */
    protected static float ease(float rawLerped) {
        float f = 1F - rawLerped;
        f = 1F - f * f * f;
        return -(f * (float) (Math.PI / 2));
    }

    /** Shared yaw-from-metadata map used by both chest variants in vanilla:
     *  2 → 180°, 4 → 90°, 5 → -90°, default (3) → 0°. */
    protected static float yawForMeta(int meta) {
        return switch (meta) {
            case 2 -> 180F;
            case 4 -> 90F;
            case 5 -> -90F;
            default -> 0F;
        };
    }

    @Override
    public void update(float partialTick) {
    }

    @Override
    protected void _delete() {
        instances.delete();
    }

    @Override
    public void updateLight(float partialTick) {
        packedLight = computeLight();
        instances.traverse(inst -> inst.light(packedLight).setChanged());
    }

    /** Default: own-block light. ChestVisual overrides to combine with the partner half. */
    protected int computeLight() {
        return computePackedLight();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        instances.traverse(consumer);
    }
}
