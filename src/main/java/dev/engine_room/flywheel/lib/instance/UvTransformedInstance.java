package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import org.lwjgl.system.MemoryUtil;

/** {@link TransformedInstance} + a per-instance atlas UV-region {@code (offsetU, offsetV, scaleU, scaleV)}.
 *  The vertex shader remaps the mesh UV into the sub-rect: {@code atlasUV = offset + baseUV * scale}. All
 *  variants sharing one atlas texture collapse to a single instancer; the sub-rect is selected per instance.
 *  The seed default {@code (0,0,1,1)} is an identity passthrough. Mirrors {@link ClipTransformedInstance}. */
public class UvTransformedInstance extends TransformedInstance {
    // pose (mat4 at OFF_POSE=12) ends at byte 76; appended like ClipTransformedInstance.OFF_SLIDE.
    static final int OFF_UV_REGION = 76;

    public UvTransformedInstance(InstanceType<? extends UvTransformedInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public UvTransformedInstance uvRegion(float offU, float offV, float scaleU, float scaleV) {
        long p = slabPtr() + OFF_UV_REGION;
        MemoryUtil.memPutFloat(p, offU);
        MemoryUtil.memPutFloat(p + 4, offV);
        MemoryUtil.memPutFloat(p + 8, scaleU);
        MemoryUtil.memPutFloat(p + 12, scaleV);
        return this;
    }

    public UvTransformedInstance uvRegion(VariantAtlas.Cell cell) {
        return uvRegion(cell.offU(), cell.offV(), cell.scaleU(), cell.scaleV());
    }
}
