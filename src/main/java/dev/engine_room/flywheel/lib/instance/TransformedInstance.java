package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.joml.Matrix4fc;

public class TransformedInstance extends ColoredLitOverlayInstance {
    static final int OFF_POSE = 12;

    public TransformedInstance(InstanceType<? extends TransformedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public TransformedInstance setTransform(Matrix4fc pose) {
        ExtraMemoryOps.putMatrix4f(slabPtr() + OFF_POSE, pose);
        return this;
    }
}
