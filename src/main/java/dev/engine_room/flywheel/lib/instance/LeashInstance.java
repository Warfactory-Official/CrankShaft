package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

/** One leash rope from {@code start} (render-origin-relative) toward {@code start + delta}: the vertex
 *  shader evaluates vanilla's rope curve, so a moving leash costs one 6-float write per frame. The seed
 *  default {@code scale} 0 collapses unposed slots to a point. */
public class LeashInstance extends AbstractInstance implements FlatLit {
    static final int OFF_LIGHT = 0;
    static final int OFF_SCALE = 4;
    static final int OFF_START = 8;
    static final int OFF_DELTA = 20;

    public LeashInstance(InstanceType<? extends LeashInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    @Override
    public LeashInstance light(int light) {
        ExtraMemoryOps.put2x16(slabPtr() + OFF_LIGHT, light);
        return this;
    }

    public LeashInstance scale(float scale) {
        MemoryUtil.memPutFloat(slabPtr() + OFF_SCALE, scale);
        return this;
    }

    public LeashInstance endpoints(float startX, float startY, float startZ,
                                   float deltaX, float deltaY, float deltaZ) {
        long p = slabPtr() + OFF_START;
        MemoryUtil.memPutFloat(p, startX);
        MemoryUtil.memPutFloat(p + 4, startY);
        MemoryUtil.memPutFloat(p + 8, startZ);
        MemoryUtil.memPutFloat(p + 12, deltaX);
        MemoryUtil.memPutFloat(p + 16, deltaY);
        MemoryUtil.memPutFloat(p + 20, deltaZ);
        return this;
    }
}
