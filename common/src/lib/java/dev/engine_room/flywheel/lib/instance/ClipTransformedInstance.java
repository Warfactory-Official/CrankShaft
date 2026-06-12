package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;

/**
 * {@link TransformedInstance} + slide vec3 + clip plane vec4 (OBJ-local, tested against {@code Position + slide}); the seed's zero plane is accepted everywhere.
 */
public class ClipTransformedInstance extends TransformedInstance {
    static final int OFF_SLIDE = 76;
    static final int OFF_PLANE = 88;

    public ClipTransformedInstance(InstanceType<? extends ClipTransformedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public ClipTransformedInstance setSlide(float x, float y, float z) {
        ExtraMemoryOps.putVector3f(slabPtr() + OFF_SLIDE, x, y, z);
        return this;
    }

    public ClipTransformedInstance setPlane(float nx, float ny, float nz, float threshold) {
        ExtraMemoryOps.putVector4f(slabPtr() + OFF_PLANE, nx, ny, nz, threshold);
        return this;
    }
}
