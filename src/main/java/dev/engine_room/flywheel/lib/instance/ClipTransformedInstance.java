package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import org.lwjgl.system.MemoryUtil;

/** {@link TransformedInstance} + per-instance slide vec3 + clip plane vec4.
 *  Used by the door variants whose animated panels disappear into their frame as they slide
 *  (SEAL, AIRLOCK, SLIDING_BLAST, CONTAINMENT). The plane equation (nx, ny, nz, threshold)
 *  is in OBJ-local space, tested against {@code slidPos = Position + slide}:
 *   - {@code clip_slab.glsl}: |dot(n, slidPos)| > threshold (two-sided slab)
 *   - {@code clip_halfspace.glsl}: dot(n, slidPos) > threshold (one-sided cap)
 *  The {@code seed} default (0,0,0,0) is a degenerate plane the slab shader accepts
 *  everywhere (|0| > 0 is false) — safe init while geometry is still being placed. */
public class ClipTransformedInstance extends TransformedInstance {
    static final int OFF_SLIDE = 76;
    static final int OFF_PLANE = 88;

    public ClipTransformedInstance(InstanceType<? extends ClipTransformedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public ClipTransformedInstance setSlide(float x, float y, float z) {
        long p = slabPtr() + OFF_SLIDE;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        return this;
    }

    public ClipTransformedInstance setPlane(float nx, float ny, float nz, float threshold) {
        long p = slabPtr() + OFF_PLANE;
        MemoryUtil.memPutFloat(p, nx);
        MemoryUtil.memPutFloat(p + 4, ny);
        MemoryUtil.memPutFloat(p + 8, nz);
        MemoryUtil.memPutFloat(p + 12, threshold);
        return this;
    }
}
