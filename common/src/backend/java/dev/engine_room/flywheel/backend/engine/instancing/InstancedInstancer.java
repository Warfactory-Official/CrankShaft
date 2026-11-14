package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.LOG_2_PAGE_SIZE;
import static dev.engine_room.flywheel.backend.engine.EngineConstants.PAGE_MASK;

public class InstancedInstancer<I extends Instance> extends BaseInstancer<I> {
    /**
     * The R32UI texel buffer of {@link #movedTexels}.
     */
    public static final String MOVED_TEXELS_NAME = "_flw_movedInstances";

    private final List<InstancedDraw> draws = new ArrayList<>();
    @Nullable
    private GpuBuffer instanceTexels;
    private int texelTexture;
    private int texelCapacity;
    protected boolean texelsReady;
    protected boolean texelsValid;
    @Nullable
    private GpuBuffer historyTexels;
    private int historyTexture;
    private int historyCapacity;
    private int uploadedCount;
    protected boolean keepHistory;
    protected boolean historyDiffers;
    @Nullable
    private GpuBuffer movedTexels;
    private int movedTexture;
    private int movedCapacity;
    protected @Nullable MemoryBlock movedScratch;
    // Indices written since the previous upload, while history is kept.
    private final BitSet moved = new BitSet();
    private boolean movesTracked;
    private boolean anyMoved;
    // While moves are tracked and compaction ran since the previous upload: the upload index of the occupant now at
    // each index below freshFrom.
    private int[] previous = new int[0];
    private boolean remapped;

    public InstancedInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        super(key, recreate);
    }

    @Nullable
    public GpuBuffer instanceTexels() {
        return instanceTexels;
    }

    public int texelTexture() {
        return texelTexture;
    }

    /**
     * Once per frame; {@code keepHistory}: retain the previous frame's instances ({@link #historyTexels}).
     */
    public void resetTexelsReady(boolean keepHistory) {
        texelsReady = false;
        historyDiffers = false;
        anyMoved = false;
        this.keepHistory = keepHistory;
        if (!keepHistory) {
            deleteHistory();
        }
    }

    /**
     * After {@link #prepareInstanceTexels}: the previous frame's instances at this frame's indices (gathered across
     * compaction), this frame's for instances added since; this frame's texels when none changed.
     */
    @Nullable
    public GpuBuffer historyTexels() {
        return historyDiffers ? historyTexels : instanceTexels;
    }

    public int historyTexture() {
        return historyDiffers ? historyTexture : texelTexture;
    }

    /**
     * After {@link #prepareInstanceTexels}: a bit per index, set for the instances written since the previous frame
     * and present in it; {@code null} when there are none.
     */
    @Nullable
    public GpuBuffer movedTexels() {
        return anyMoved ? movedTexels : null;
    }

    public int movedTexture() {
        return movedTexture;
    }

    public void prepareInstanceTexels() {
        if (texelsReady) {
            return;
        }
        int count = instanceCount();
        if (count == 0) {
            return;
        }
        if (texelsValid && !needsGrow(count)) {
            texelsReady = true;
            return;
        }

        // The previous upload holds the previous frame's instances; indices below freshFrom hold occupants it had.
        int kept = Math.min(Math.min(uploadedCount, count), freshFrom);
        if (keepHistory) {
            prepareHistory(count);
            if (!remapped) {
                copyHistory(0, 0, kept);
            } else {
                for (int i = 0; i < kept; ) {
                    int from = previous[i];
                    int end = i + 1;
                    while (end < kept && previous[end] == from + end - i) {
                        end++;
                    }
                    copyHistory(from, i, end - i);
                    i = end;
                }
            }
            uploadMoved(count, kept);
        }
        upload(count);
        if (keepHistory && kept < count) {
            uploadHistory(kept, count);
        }
        historyDiffers = keepHistory;
        uploadedCount = count;
        freshFrom = Integer.MAX_VALUE;
        remapped = false;
        texelsValid = true;
        texelsReady = true;
    }

    protected boolean needsGrow(int count) {
        return instanceTexels == null || texelCapacity < count;
    }

    // Before this upload's copyHistory and uploadHistory.
    protected void prepareHistory(int count) {
        if (historyTexels == null || historyCapacity < count) {
            if (historyTexels != null) {
                historyTexels.close();
            }
            historyTexels = RenderSystem.getDevice()
                                        .createBuffer(() -> "flywheel instance history",
                                                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                                                (long) count * instanceStride);
            historyCapacity = count;
            if (historyTexture == 0) {
                historyTexture = GlStateManager._genTexture();
            }
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, historyTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32UI, ((GlBuffer) historyTexels).handle());
        }
    }

    // Before upload: the previous upload's instances [from, from + length) into history at [to, to + length).
    protected void copyHistory(int from, int to, int length) {
        if (length > 0) {
            RenderSystem.getDevice()
                        .createCommandEncoder()
                        .copyToBuffer(instanceTexels.slice(from * instanceStride, length * instanceStride),
                                historyTexels.slice(to * instanceStride, length * instanceStride));
        }
    }

    protected void upload(int count) {
        long needBytes = (long) count * instanceStride;
        if (needsGrow(count)) {
            GlCompat.requireTextureBufferSize(needBytes, 16, "flywheel instances");
            if (needBytes > Integer.MAX_VALUE) {
                throw new BackendUnavailableException("flywheel instances exceed the upload buffer limit");
            }
            if (instanceTexels != null) {
                instanceTexels.close();
            }
            instanceTexels = RenderSystem.getDevice()
                                         .createBuffer(() -> "flywheel instances",
                                                 GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST
                                                         | GpuBuffer.USAGE_COPY_SRC, needBytes);
            texelCapacity = count;
            if (texelTexture == 0) {
                texelTexture = GlStateManager._genTexture();
            }
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texelTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32UI, ((GlBuffer) instanceTexels).handle());
        }
        // updateBuffer migrated every page into the slab: contiguous.
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(instanceTexels.slice(0L, needBytes),
                            MemoryUtil.memByteBuffer(slabBlocks[0], (int) needBytes));
    }

    // This frame's instances [from, to), added since the previous upload, into history.
    protected void uploadHistory(int from, int to) {
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(historyTexels.slice(from * instanceStride, (to - from) * instanceStride),
                            MemoryUtil.memByteBuffer(slabBlocks[0] + from * instanceStride,
                                    (int) ((to - from) * instanceStride)));
    }

    // Writes before tracking began are unknown: every kept index then counts as moved.
    private void uploadMoved(int count, int kept) {
        if (!movesTracked) {
            moved.set(0, kept);
        } else if (moved.length() > kept) {
            moved.clear(kept, moved.length());
        }
        uploadMoved(moved, count);
        moved.clear();
        movesTracked = true;
    }

    protected void uploadMoved(BitSet bits, int count) {
        anyMoved = !bits.isEmpty();
        if (!anyMoved) {
            return;
        }
        int words = (count + 31) >>> 5;
        long bytes = (long) words * Integer.BYTES;
        if (movedTexels == null || movedCapacity < words) {
            if (movedTexels != null) {
                movedTexels.close();
            }
            movedTexels = RenderSystem.getDevice()
                                      .createBuffer(() -> "flywheel moved instances",
                                              GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
            movedCapacity = words;
            if (movedTexture == 0) {
                movedTexture = GlStateManager._genTexture();
            }
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, movedTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_R32UI, ((GlBuffer) movedTexels).handle());
        }
        long ptr = movedScratch(bytes);
        MemoryUtil.memSet(ptr, 0, bytes);
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            setBit(ptr, i);
        }
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(movedTexels.slice(0L, bytes), MemoryUtil.memByteBuffer(ptr, (int) bytes));
    }

    protected final long movedScratch(long bytes) {
        if (movedScratch == null) {
            movedScratch = MemoryBlock.malloc(bytes);
        } else if (movedScratch.size() < bytes) {
            movedScratch = movedScratch.realloc(bytes);
        }
        return movedScratch.ptr();
    }

    protected static void setBit(long words, int bit) {
        long word = words + (long) (bit >>> 5) * Integer.BYTES;
        MemoryUtil.memPutInt(word, MemoryUtil.memGetInt(word) | 1 << (bit & 31));
    }

    protected void deleteHistory() {
        if (historyTexels != null) {
            historyTexels.close();
            historyTexels = null;
            historyCapacity = 0;
        }
        if (historyTexture != 0) {
            GlStateManager._deleteTexture(historyTexture);
            historyTexture = 0;
        }
        if (movedTexels != null) {
            movedTexels.close();
            movedTexels = null;
            movedCapacity = 0;
        }
        if (movedTexture != 0) {
            GlStateManager._deleteTexture(movedTexture);
            movedTexture = 0;
        }
        if (movedScratch != null) {
            movedScratch.free();
            movedScratch = null;
        }
        moved.clear();
        movesTracked = false;
        remapped = false;
    }

    public List<InstancedDraw> draws() {
        return draws;
    }

    public void init() {
    }

    /**
     * Once per frame, after {@link #resetTexelsReady}.
     */
    public void updateBuffer() {
        if (prepareUpload() == null || changed.isEmpty()) {
            return;
        }
        if (keepHistory) {
            for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
                moved.set(i);
            }
        }
        texelsValid = false;
        changed.clear();
    }

    @Override
    public void parallelUpdate() {
        if (deleted.isEmpty()) {
            return;
        }

        final int oldSize = this.instances.size();
        int removeCount = deleted.cardinality();

        if (oldSize == removeCount) {
            clear();
            return;
        }

        final int newSize = oldSize - removeCount;

        int writePos = deleted.nextSetBit(0);

        // Tracked moves: moved bits and upload indices follow their instances, so the next upload gathers their
        // previous frame; untracked, shifted instances count as added.
        boolean tracked = movesTracked;
        int fresh = Math.min(freshFrom, oldSize);
        int deletedBelowFresh = 0;
        if (tracked) {
            for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
                moved.set(i);
            }
            changed.clear();
            texelsValid = false;
            if (!remapped) {
                if (previous.length < fresh) {
                    previous = new int[fresh];
                }
                for (int i = 0; i < fresh; i++) {
                    previous[i] = i;
                }
                remapped = true;
            }
            for (int i = deleted.nextSetBit(0); i >= 0 && i < fresh; i = deleted.nextSetBit(i + 1)) {
                deletedBelowFresh++;
            }
        } else {
            if (writePos < newSize) {
                changed.set(writePos, newSize);
            }

            changed.clear(newSize, oldSize);
        }

        long[] blocks = slabBlocks;
        for (int scanPos = writePos; (scanPos < oldSize) && (writePos < newSize); scanPos++, writePos++) {
            scanPos = deleted.nextClearBit(scanPos);

            if (scanPos != writePos) {
                if (!tracked) {
                    freshFrom = Math.min(freshFrom, writePos);
                } else {
                    if (scanPos < fresh) {
                        previous[writePos] = previous[scanPos];
                    }
                    moved.set(writePos, moved.get(scanPos));
                }
                var handle = handles.get(scanPos);
                I instance = instances.get(scanPos);

                handles.set(writePos, handle);
                instances.set(writePos, instance);

                long srcPtr = blocks[scanPos >>> LOG_2_PAGE_SIZE] + (long) (scanPos & PAGE_MASK) * instanceStride;
                long dstPtr = blocks[writePos >>> LOG_2_PAGE_SIZE] + (long) (writePos & PAGE_MASK) * instanceStride;
                MemoryUtil.memCopy(srcPtr, dstPtr, instanceStride);

                handle.index = writePos;
            }
        }
        if (tracked) {
            moved.clear(newSize, oldSize);
            if (fresh < oldSize) {
                freshFrom = fresh - deletedBelowFresh;
            }
        }

        deleted.clear();
        instances.subList(newSize, oldSize)
                 .clear();
        handles.subList(newSize, oldSize)
               .clear();
    }

    @Override
    public void delete() {
        for (InstancedDraw instancedDraw : draws) {
            instancedDraw.delete();
        }
        if (instanceTexels != null) {
            instanceTexels.close();
            instanceTexels = null;
        }
        if (texelTexture != 0) {
            GlStateManager._deleteTexture(texelTexture);
            texelTexture = 0;
        }
        deleteHistory();
        clear();
        freeGlResources();
    }

    public void addDrawCall(InstancedDraw instancedDraw) {
        draws.add(instancedDraw);
    }
}
