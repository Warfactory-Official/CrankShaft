package dev.engine_room.flywheel.backend.engine.terrain;

/**
 * Sparse optional metadata beside a native terrain mesh; vertex bytes retain their original format.
 */
public interface TerrainReceiverMesh {
    int[] flywheel$receiverIds();

    void flywheel$receiverIds(int[] ids);

    interface Quad {
        int flywheel$receiverId();

        void flywheel$receiverId(int id);
    }
}
