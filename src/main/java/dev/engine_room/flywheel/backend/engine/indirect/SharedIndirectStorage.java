package dev.engine_room.flywheel.backend.engine.indirect;

public final class SharedIndirectStorage {
    public final SharedStorageArena objects = new SharedStorageArena(4);
    public final SharedStorageArena indices = new SharedStorageArena(4);
    public final SharedStorageArena models = new SharedStorageArena(IndirectBuffers.MODEL_STRIDE);
    public final SharedStorageArena draws = new SharedStorageArena(IndirectBuffers.DRAW_COMMAND_STRIDE);

    public void delete() {
        objects.delete();
        indices.delete();
        models.delete();
        draws.delete();
    }
}
