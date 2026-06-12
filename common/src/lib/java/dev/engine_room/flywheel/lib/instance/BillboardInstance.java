package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

/**
 * A camera-facing sprite anchored at {@code position}: the vertex shader orients the mesh toward the camera plane.
 */
public class BillboardInstance extends ColoredLitOverlayInstance {
    static final int OFF_POSITION = 12;
    static final int OFF_SIZE = 24;
    static final int OFF_UV_REGION = 28;

    public BillboardInstance(InstanceType<? extends BillboardInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public BillboardInstance position(float x, float y, float z) {
        ExtraMemoryOps.putVector3f(slabPtr() + OFF_POSITION, x, y, z);
        return this;
    }

    public BillboardInstance size(float size) {
        MemoryUtil.memPutFloat(slabPtr() + OFF_SIZE, size);
        return this;
    }

    public BillboardInstance uvRegion(float offU, float offV, float scaleU, float scaleV) {
        ExtraMemoryOps.putVector4f(slabPtr() + OFF_UV_REGION, offU, offV, scaleU, scaleV);
        return this;
    }
}
