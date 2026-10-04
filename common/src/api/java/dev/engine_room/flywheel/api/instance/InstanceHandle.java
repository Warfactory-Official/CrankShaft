package dev.engine_room.flywheel.api.instance;

import dev.engine_room.flywheel.api.backend.BackendImplemented;

@BackendImplemented
public interface InstanceHandle {
    void setChanged();

    void setDeleted();

    boolean isVisible();

    void setVisible(boolean visible);

    /**
     * Current off-heap address of this instance's packed data. Query again after a storage transition or
     * frame barrier; compaction and upload migration can change the address. Hidden data remains writable
     * and is restored on reveal; deleted handles return a shared write-only trash slot.
     */
    long slabPtr();
}
