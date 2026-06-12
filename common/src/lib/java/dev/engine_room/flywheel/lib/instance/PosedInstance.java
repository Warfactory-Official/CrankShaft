package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

public class PosedInstance extends ColoredLitOverlayInstance {
    static final int OFF_POSE = 12;
    static final int OFF_NORMAL = 76;

    public PosedInstance(InstanceType<? extends PosedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public PosedInstance setTransform(Matrix4fc pose, Matrix3fc normal) {
        long p = slabPtr();
        ExtraMemoryOps.putMatrix4f(p + OFF_POSE, pose);
        ExtraMemoryOps.putMatrix3f(p + OFF_NORMAL, normal);
        return this;
    }
}
