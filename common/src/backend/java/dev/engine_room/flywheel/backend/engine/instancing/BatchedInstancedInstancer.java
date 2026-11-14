package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class BatchedInstancedInstancer<I extends Instance> extends InstancedInstancer<I> {
    private final int batchCapacity;
    private final List<Batch> batches = new ArrayList<>();

    public BatchedInstancedInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        super(key, recreate);
        long maxBytes = Math.min((long) GlCompat.MAX_TEXTURE_BUFFER_SIZE * 16, Integer.MAX_VALUE);
        batchCapacity = (int) (maxBytes / instanceStride);
        if (batchCapacity == 0) {
            throw new BackendUnavailableException("Instance stride exceeds the texture-buffer limit");
        }
    }

    public int batchCount() {
        int count = instanceCount();
        return count == 0 ? 0 : (count - 1) / batchCapacity + 1;
    }

    public int batchForInstance(int index) {
        return index / batchCapacity;
    }

    public int batchStart(int batch) {
        return batch * batchCapacity;
    }

    public int batchSize(int batch) {
        return Math.min(batchCapacity, instanceCount() - batchStart(batch));
    }

    public GpuBuffer instanceTexels(int batch) {
        return batches.get(batch).buffer;
    }

    public int texelTexture(int batch) {
        return batches.get(batch).texture;
    }

    /**
     * {@link #historyTexels()} for one batch, at its local indices.
     */
    public GpuBuffer historyTexels(int batch) {
        Batch storage = batches.get(batch);
        return historyDiffers ? storage.history : storage.buffer;
    }

    public int historyTexture(int batch) {
        Batch storage = batches.get(batch);
        return historyDiffers ? storage.historyTexture : storage.texture;
    }

    /**
     * {@link #movedTexels()} for one batch, a bit per local index.
     */
    @Nullable
    public GpuBuffer movedTexels(int batch) {
        Batch storage = batches.get(batch);
        return storage.anyMoved ? storage.moved : null;
    }

    public int movedTexture(int batch) {
        return batches.get(batch).movedTexture;
    }

    @Override
    public void resetTexelsReady(boolean keepHistory) {
        super.resetTexelsReady(keepHistory);
        for (Batch batch : batches) {
            batch.anyMoved = false;
        }
    }

    @Override
    protected boolean needsGrow(int _) {
        return batches.size() < batchCount();
    }

    @Override
    protected void prepareHistory(int _) {
        for (int batch = 0, batchCount = batchCount(); batch < batchCount; batch++) {
            Batch storage = batch(batch);
            long bytes = (long) batchSize(batch) * instanceStride;
            if (storage.history == null || storage.history.size() < bytes) {
                if (storage.history != null) {
                    storage.history.close();
                }
                storage.history = RenderSystem.getDevice().createBuffer(() -> "flywheel instance history",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
                if (storage.historyTexture == 0) {
                    storage.historyTexture = GlStateManager._genTexture();
                }
                GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, storage.historyTexture);
                GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32UI, ((GlBuffer) storage.history).handle());
            }
        }
    }

    // Split where either side crosses a batch.
    @Override
    protected void copyHistory(int from, int to, int length) {
        while (length > 0) {
            int fromLocal = from % batchCapacity;
            int toLocal = to % batchCapacity;
            int run = Math.min(length, batchCapacity - Math.max(fromLocal, toLocal));
            RenderSystem.getDevice().createCommandEncoder().copyToBuffer(
                    batches.get(from / batchCapacity).buffer.slice(fromLocal * instanceStride, run * instanceStride),
                    batches.get(to / batchCapacity).history.slice(toLocal * instanceStride, run * instanceStride));
            from += run;
            to += run;
            length -= run;
        }
    }

    @Override
    protected void upload(int _) {
        for (int batch = 0, batchCount = batchCount(); batch < batchCount; batch++) {
            Batch storage = batch(batch);
            long bytes = (long) batchSize(batch) * instanceStride;
            if (storage.buffer == null || storage.buffer.size() < bytes) {
                GlCompat.requireTextureBufferSize(bytes, 16, "flywheel instances");
                if (storage.buffer != null) {
                    storage.buffer.close();
                }
                storage.buffer = RenderSystem.getDevice().createBuffer(() -> "flywheel instances",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC,
                        bytes);
                if (storage.texture == 0) {
                    storage.texture = GlStateManager._genTexture();
                }
                GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, storage.texture);
                GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32UI, ((GlBuffer) storage.buffer).handle());
            }
            long pointer = slabBlocks[0] + (long) batchStart(batch) * instanceStride;
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(storage.buffer.slice(0L, bytes),
                    MemoryUtil.memByteBuffer(pointer, (int) bytes));
        }
    }

    @Override
    protected void uploadHistory(int from, int to) {
        while (from < to) {
            int local = from % batchCapacity;
            int run = Math.min(to - from, batchCapacity - local);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                    batches.get(from / batchCapacity).history.slice(local * instanceStride, run * instanceStride),
                    MemoryUtil.memByteBuffer(slabBlocks[0] + from * instanceStride, (int) (run * instanceStride)));
            from += run;
        }
    }

    @Override
    protected void uploadMoved(BitSet bits, int _) {
        int batchWords = (batchCapacity + 31) >>> 5;
        long batchBytes = (long) batchWords * Integer.BYTES;
        int batchCount = batchCount();
        long ptr = movedScratch(batchBytes * batchCount);
        MemoryUtil.memSet(ptr, 0, batchBytes * batchCount);
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            int batch = i / batchCapacity;
            batches.get(batch).anyMoved = true;
            setBit(ptr + batch * batchBytes, i - batch * batchCapacity);
        }
        for (int batch = 0; batch < batchCount; batch++) {
            Batch storage = batches.get(batch);
            if (!storage.anyMoved) {
                continue;
            }
            if (storage.moved == null) {
                storage.moved = RenderSystem.getDevice().createBuffer(() -> "flywheel moved instances",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, batchBytes);
                storage.movedTexture = GlStateManager._genTexture();
                GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, storage.movedTexture);
                GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_R32UI, ((GlBuffer) storage.moved).handle());
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(storage.moved.slice(),
                    MemoryUtil.memByteBuffer(ptr + batch * batchBytes, (int) batchBytes));
        }
    }

    @Override
    protected void deleteHistory() {
        super.deleteHistory();
        for (Batch batch : batches) {
            batch.deleteHistory();
        }
    }

    @Override
    public void delete() {
        for (Batch batch : batches) {
            if (batch.buffer != null) {
                batch.buffer.close();
            }
            if (batch.texture != 0) {
                GlStateManager._deleteTexture(batch.texture);
            }
        }
        super.delete();
        batches.clear();
    }

    private Batch batch(int batch) {
        if (batch == batches.size()) {
            batches.add(new Batch());
        }
        return batches.get(batch);
    }

    private static final class Batch {
        private GpuBuffer buffer;
        private int texture;
        private @Nullable GpuBuffer history;
        private int historyTexture;
        private @Nullable GpuBuffer moved;
        private int movedTexture;
        private boolean anyMoved;

        private void deleteHistory() {
            if (history != null) {
                history.close();
                history = null;
            }
            if (historyTexture != 0) {
                GlStateManager._deleteTexture(historyTexture);
                historyTexture = 0;
            }
            if (moved != null) {
                moved.close();
                moved = null;
            }
            if (movedTexture != 0) {
                GlStateManager._deleteTexture(movedTexture);
                movedTexture = 0;
            }
            anyMoved = false;
        }
    }
}
