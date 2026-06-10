package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import org.lwjgl.system.MemoryUtil;

/** A camera-facing sprite anchored at {@code position}: the vertex shader orients the mesh toward the
 *  camera plane (vanilla's billboard rotation), so orientation never costs a per-frame CPU write.
 *  {@code size} uniformly scales the mesh about the anchor; the seed default 0 keeps unposed slots
 *  degenerate (invisible). {@code uvRegion} remaps mesh UV like {@link UvTransformedInstance}. */
public class BillboardInstance extends ColoredLitOverlayInstance {
    static final int OFF_POSITION = 12;
    static final int OFF_SIZE = 24;
    static final int OFF_UV_REGION = 28;

    public BillboardInstance(InstanceType<? extends BillboardInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public BillboardInstance position(float x, float y, float z) {
        long p = slabPtr() + OFF_POSITION;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        return this;
    }

    public BillboardInstance size(float size) {
        MemoryUtil.memPutFloat(slabPtr() + OFF_SIZE, size);
        return this;
    }

    public BillboardInstance uvRegion(float offU, float offV, float scaleU, float scaleV) {
        long p = slabPtr() + OFF_UV_REGION;
        MemoryUtil.memPutFloat(p, offU);
        MemoryUtil.memPutFloat(p + 4, offV);
        MemoryUtil.memPutFloat(p + 8, scaleU);
        MemoryUtil.memPutFloat(p + 12, scaleV);
        return this;
    }
}
