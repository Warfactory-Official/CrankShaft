package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;

public abstract class ColoredLitOverlayInstance extends ColoredLitInstance {
    static final int OFF_OVERLAY = 4;

    public ColoredLitOverlayInstance(InstanceType<? extends ColoredLitOverlayInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public ColoredLitOverlayInstance overlay(int overlay) {
        ExtraMemoryOps.put2x16(slabPtr() + OFF_OVERLAY, overlay);
        return this;
    }
}
