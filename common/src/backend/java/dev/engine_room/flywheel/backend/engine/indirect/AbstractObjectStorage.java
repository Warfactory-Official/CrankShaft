package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.math.MoreMath;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.*;

public abstract class AbstractObjectStorage {
    public static final int INVALID_PAGE = -1;
    public static final int DESCRIPTOR_SIZE_BYTES = Integer.BYTES * 4;

    private final Int2ObjectOpenHashMap<IntArrayList> freeSlots = new Int2ObjectOpenHashMap<>();
    private long[] slotBaseByte = new long[64];
    private int slotCount;
    private long bytesUsed;

    public int pageSlotCount() {
        return slotCount;
    }

    public long bytesUsed() {
        return bytesUsed;
    }

    public Mapping createMapping(InstanceType<?> type) {
        return new Mapping(type);
    }

    private int allocPage(int pageBytes) {
        IntArrayList free = freeSlots.get(pageBytes);
        if (free != null && !free.isEmpty()) {
            return free.removeInt(free.size() - 1);
        }
        int slot = slotCount++;
        if (slot >= slotBaseByte.length) {
            slotBaseByte = Arrays.copyOf(slotBaseByte, slotBaseByte.length * 2);
        }
        slotBaseByte[slot] = bytesUsed;
        bytesUsed += pageBytes;
        ensureCapacities(bytesUsed, slotCount);
        return slot;
    }

    private void freePage(int slot, int pageBytes) {
        freeSlots.computeIfAbsent(pageBytes, k -> new IntArrayList())
                 .add(slot);
        writeDescriptor(slot, 0, 0, 0, 0);
    }

    protected abstract void ensureCapacities(long objectBytes, int slotCount);

    protected abstract void writeDescriptor(int slot, int modelIndex, int validBits, int baseUint, int typeInfo);

    public abstract void delete();

    public final class Mapping implements ObjectMapping {
        private static final int[] EMPTY_ALLOCATION = new int[0];

        private final int typeInfo;
        private final int strideUints;
        private final int pageBytes;
        private int[] pages = EMPTY_ALLOCATION;

        private Mapping(InstanceType<?> type) {
            long strideBytes = MoreMath.align4(type.layout()
                                                   .byteSize());
            strideUints = (int) (strideBytes >> 2);
            if (strideUints >= 1 << 16) {
                throw new IllegalArgumentException("Instance stride too large for typeInfo packing: " + strideBytes);
            }
            typeInfo = InstanceTypeIds.id(type) | (strideUints << 16);
            pageBytes = (int) (PAGE_SIZE * strideBytes);
        }

        @Override
        public void updatePage(int index, int modelIndex, int validBits) {
            if (validBits == 0) {
                holePunch(index);
                return;
            }
            int slot = pages[index];
            if (slot == INVALID_PAGE) {
                slot = allocPage(pageBytes);
                pages[index] = slot;
            }
            writeDescriptor(slot, modelIndex, validBits, (int) (slotBaseByte[slot] >> 2), typeInfo);
        }

        private void holePunch(int index) {
            int slot = pages[index];
            if (slot != INVALID_PAGE) {
                freePage(slot, pageBytes);
                pages[index] = INVALID_PAGE;
            }
        }

        @Override
        public void updateCount(int newLength) {
            int oldLength = pages.length;
            if (oldLength > newLength) {
                for (int i = oldLength - 1; i >= newLength; i--) {
                    holePunch(i);
                }
                pages = Arrays.copyOf(pages, newLength);
            } else if (oldLength < newLength) {
                pages = Arrays.copyOf(pages, newLength);
                for (int i = oldLength; i < newLength; i++) {
                    pages[i] = allocPage(pageBytes);
                    writeDescriptor(pages[i], 0, 0, 0, 0);
                }
            }
        }

        @Override
        public int pageCount() {
            return pages.length;
        }

        @Override
        public long page2ByteOffset(int index) {
            int slot = pages[index];
            return slot == INVALID_PAGE ? -1 : slotBaseByte[slot];
        }

        @Override
        public void delete() {
            for (int slot : pages) {
                if (slot != INVALID_PAGE) {
                    freePage(slot, pageBytes);
                }
            }
            pages = EMPTY_ALLOCATION;
        }

        @Override
        public int objectIndex2UintOffset(int objectIndex) {
            int slot = pages[objectIndex >> LOG_2_PAGE_SIZE];
            return (int) (slotBaseByte[slot] >> 2) + (objectIndex & PAGE_MASK) * strideUints;
        }
    }
}
