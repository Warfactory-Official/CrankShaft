package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;

public abstract class AbstractInstance implements Instance {
    protected final InstanceType<?> type;
    protected final InstanceHandle handle;

    protected AbstractInstance(InstanceType<?> type, InstanceHandle handle) {
        this.type = type;
        this.handle = handle;
    }

    @Override
    public final InstanceType<?> type() {
        return type;
    }

    @Override
    public final InstanceHandle handle() {
        return handle;
    }

    @Override
    public final void setChanged() {
        handle.setChanged();
    }

    protected final long slabPtr() {
        return handle.slabPtr();
    }

    @Override
    public final void delete() {
        handle.setDeleted();
    }

    @Override
    public final void setVisible(boolean visible) {
        handle.setVisible(visible);
    }
}
