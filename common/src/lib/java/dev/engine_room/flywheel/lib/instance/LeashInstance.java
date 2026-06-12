package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

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
        ExtraMemoryOps.putVector3f(p, startX, startY, startZ);
        ExtraMemoryOps.putVector3f(p + 12, deltaX, deltaY, deltaZ);
        return this;
    }
}
