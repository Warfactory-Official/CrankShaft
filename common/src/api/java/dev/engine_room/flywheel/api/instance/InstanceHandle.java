package dev.engine_room.flywheel.api.instance;

import dev.engine_room.flywheel.api.backend.BackendImplemented;

@BackendImplemented
public interface InstanceHandle {
    void setChanged();

    void setDeleted();

    boolean isVisible();

    void setVisible(boolean visible);

    /**
     * Off-heap address of this instance's std140-packed slot, stable for its lifetime;
     * hidden/deleted handles return a shared trash slot (writes there are harmless).
     */
    long slabPtr();
}
