package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

public class OrientedInstance extends ColoredLitOverlayInstance {
    static final int OFF_POS = 12;
    static final int OFF_PIVOT = 24;
    static final int OFF_ROT = 36;

    public OrientedInstance(InstanceType<? extends OrientedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public OrientedInstance position(float x, float y, float z) {
        long p = slabPtr() + OFF_POS;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        return this;
    }

    public OrientedInstance position(Vector3fc pos) {
        return position(pos.x(), pos.y(), pos.z());
    }

    public OrientedInstance position(Vec3i pos) {
        return position(pos.getX(), pos.getY(), pos.getZ());
    }

    public OrientedInstance position(Vec3d pos) {
        return position((float) pos.x, (float) pos.y, (float) pos.z);
    }

    public OrientedInstance zeroPosition() {
        return position(0, 0, 0);
    }

    public OrientedInstance pivot(float x, float y, float z) {
        long p = slabPtr() + OFF_PIVOT;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        return this;
    }

    public OrientedInstance pivot(Vector3fc pos) {
        return pivot(pos.x(), pos.y(), pos.z());
    }

    public OrientedInstance pivot(Vec3i pos) {
        return pivot(pos.getX(), pos.getY(), pos.getZ());
    }

    public OrientedInstance pivot(Vec3d pos) {
        return pivot((float) pos.x, (float) pos.y, (float) pos.z);
    }

    public OrientedInstance centerPivot() {
        return pivot(0.5f, 0.5f, 0.5f);
    }

    public OrientedInstance rotation(Quaternionfc q) {
        ExtraMemoryOps.putQuaternionf(slabPtr() + OFF_ROT, q);
        return this;
    }

    public OrientedInstance rotation(float x, float y, float z, float w) {
        long p = slabPtr() + OFF_ROT;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, w);
        return this;
    }
}
