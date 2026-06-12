package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;

/**
 * {@link TransformedInstance} + a per-instance atlas UV-region; the vertex shader remaps the mesh UV into the sub-rect.
 */
public class UvTransformedInstance extends TransformedInstance {
    static final int OFF_UV_REGION = 76;

    public UvTransformedInstance(InstanceType<? extends UvTransformedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public UvTransformedInstance uvRegion(float offU, float offV, float scaleU, float scaleV) {
        ExtraMemoryOps.putVector4f(slabPtr() + OFF_UV_REGION, offU, offV, scaleU, scaleV);
        return this;
    }
}
