package dev.engine_room.flywheel.backend.engine.indirect;

public interface ObjectMapping {
    void updatePage(int index, int modelIndex, int validBits);

    void updateCount(int newLength);

    int pageCount();

    long page2ByteOffset(int index);

    int objectIndex2UintOffset(int objectIndex);

    /**
     * The descriptor slot of the page, {@link AbstractObjectStorage#INVALID_PAGE} without one.
     */
    int pageSlot(int index);

    /**
     * Changes whenever a page's slot does.
     */
    int slotVersion();

    void delete();
}
