package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayList;
import java.util.List;

public final class VkTerrainResidentBuffers implements TerrainResidentBuffers {
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int TRANSFER_SRC = VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private static final int TRANSFER_DST = VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final List<Buffer> all = new ArrayList<>();
    private int readParity;

    public void setReadParity(int parity) {
        readParity = parity;
    }

    public boolean hasDirty(int parity) {
        for (Buffer b : all) {
            if (b.dirtyHi[parity] != 0L) {
                return true;
            }
        }
        return false;
    }

    public void recordUploads(VkCommandBuffer cmd, int parity) {
        boolean any = false;
        for (Buffer b : all) {
            any |= b.recordCopy(cmd, parity);
        }
        if (any) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                                                                .sType$Default()
                                                                .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                                                                .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
                // dst = ALL_COMMANDS: the metadata is consumed by compute (cull/emit), vertex/fragment (MDI + fade),
                VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
            }
        }
    }

    @Override
    public TerrainResidentBuffer createMirror(long stride) {
        Buffer b = new Buffer(stride);
        all.add(b);
        return b;
    }

    @Override
    public TerrainResidentBuffer createDynamic() {
        Buffer b = new Buffer(Integer.BYTES);
        all.add(b);
        return b;
    }

    // VK folds host writes into the device copies via recordUploads(cmd, parity), driven by the manager on a command
    @Override
    public void flushPendingWrites() {
    }

    @Override
    public void flushBeforeGrow() {
    }

    private final class Buffer implements TerrainResidentBuffer {
        private final VkBuffer[] staging = new VkBuffer[2];
        private final VkBuffer[] device = new VkBuffer[2];
        // Dirty span [lo, hi) per parity -- the byte range changed since that parity was last synced. hi == 0 => clean.
        private final long[] dirtyLo = {0L, 0L};
        private final long[] dirtyHi = {0L, 0L};
        private long shadowPtr;
        private long shadowBytes;

        Buffer(long initialBytes) {
            long bytes = Math.max(initialBytes, Integer.BYTES);
            this.shadowBytes = bytes;
            this.shadowPtr = FlwMemoryTracker.malloc(bytes);
            MemoryUtil.memSet(shadowPtr, 0, bytes);
            try {
                staging[0] = new VkBuffer(TRANSFER_SRC, bytes);
                staging[1] = new VkBuffer(TRANSFER_SRC, bytes);
                device[0] = new VkBuffer(STORAGE | TRANSFER_DST, bytes, true);
                device[1] = new VkBuffer(STORAGE | TRANSFER_DST, bytes, true);
            } catch (Throwable t) {
                if (device[0] != null) {
                    device[0].delete();
                }
                if (staging[1] != null) {
                    staging[1].delete();
                }
                if (staging[0] != null) {
                    staging[0].delete();
                }
                MemoryUtil.nmemFree(shadowPtr);
                shadowPtr = 0L;
                throw t;
            }
        }

        @Override
        public boolean ensureCapacity(long bytes) {
            if (bytes <= shadowBytes) {
                return false;
            }
            long newBytes = Math.max(bytes, shadowBytes * 2);
            // Tracker realloc throws OutOfMemoryError on a NULL return; a raw nmemRealloc would hand the
            // following memSet a near-null pointer.
            shadowPtr = FlwMemoryTracker.realloc(shadowPtr, newBytes);
            MemoryUtil.memSet(shadowPtr + shadowBytes, 0, newBytes - shadowBytes);
            shadowBytes = newBytes;
            staging[0].ensureCapacity(newBytes);
            staging[1].ensureCapacity(newBytes);
            device[0].ensureCapacity(newBytes);
            device[1].ensureCapacity(newBytes);
            markDirty(0L, newBytes);
            return true;
        }

        @Override
        public long byteCapacity() {
            return shadowBytes;
        }

        @Override
        public void write(long offset, long srcPtr, long size) {
            if (offset + size > shadowBytes) {
                return;
            }
            MemoryUtil.memCopy(srcPtr, shadowPtr + offset, size);
            markDirty(offset, offset + size);
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
            if (offset + size > shadowBytes) {
                return;
            }
            long ptr = shadowPtr + offset;
            if (fillWord == 0) {
                MemoryUtil.memSet(ptr, 0, size);
            } else {
                long words = size / Integer.BYTES;
                for (long i = 0; i < words; i++) {
                    MemoryUtil.memPutInt(ptr + i * Integer.BYTES, fillWord);
                }
            }
            markDirty(offset, offset + size);
        }

        private void markDirty(long lo, long hi) {
            for (int p = 0; p < 2; p++) {
                if (dirtyHi[p] == 0L) {
                    dirtyLo[p] = lo;
                    dirtyHi[p] = hi;
                } else {
                    if (lo < dirtyLo[p]) {
                        dirtyLo[p] = lo;
                    }
                    if (hi > dirtyHi[p]) {
                        dirtyHi[p] = hi;
                    }
                }
            }
        }

        boolean recordCopy(VkCommandBuffer cmd, int parity) {
            long hi = dirtyHi[parity];
            if (hi == 0L) {
                return false;
            }
            long lo = dirtyLo[parity];
            long size = hi - lo;
            VkBuffer stage = staging[parity];
            VkBuffer dev = device[parity];
            MemoryUtil.memCopy(shadowPtr + lo, stage.mappedAddress() + lo, size);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(lo).dstOffset(lo).size(size);
                VK12.vkCmdCopyBuffer(cmd, stage.vkBuffer(), dev.vkBuffer(), region);
            }
            dirtyLo[parity] = 0L;
            dirtyHi[parity] = 0L;
            return true;
        }

        @Override
        public long vkBuffer() {
            return device[readParity].vkBuffer();
        }

        @Override
        public void delete() {
            staging[0].delete();
            staging[1].delete();
            device[0].delete();
            device[1].delete();
            if (shadowPtr != 0L) {
                MemoryUtil.nmemFree(shadowPtr);
                shadowPtr = 0L;
            }
        }
    }
}
