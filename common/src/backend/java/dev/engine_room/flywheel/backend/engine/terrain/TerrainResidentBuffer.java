package dev.engine_room.flywheel.backend.engine.terrain;

public interface TerrainResidentBuffer {
    boolean ensureCapacity(long bytes);

    long byteCapacity();

    void write(long offset, long srcPtr, long size);

    void clearRange(long offset, long size, int fillWord);

    void delete();

    default long deviceAddress() {
        throw new UnsupportedOperationException();
    }

    default int handle() {
        throw new UnsupportedOperationException();
    }

    default long vkBuffer() {
        throw new UnsupportedOperationException();
    }
}
