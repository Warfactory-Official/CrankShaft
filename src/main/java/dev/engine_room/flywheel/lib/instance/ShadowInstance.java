package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import org.lwjgl.system.MemoryUtil;

public class ShadowInstance extends AbstractInstance {
    public ShadowInstance(InstanceType<? extends ShadowInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public ShadowInstance write(float x, float y, float z, float entityX, float entityZ,
                                float sizeX, float sizeZ, float alpha, float radius) {
        long p = slabPtr();
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, entityX);
        MemoryUtil.memPutFloat(p + 16, entityZ);
        MemoryUtil.memPutFloat(p + 20, sizeX);
        MemoryUtil.memPutFloat(p + 24, sizeZ);
        MemoryUtil.memPutFloat(p + 28, alpha);
        MemoryUtil.memPutFloat(p + 32, radius);
        return this;
    }
}
