package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
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

    @Override
    public void prepareInstanceTexels() {
        if (texelsReady || instanceCount() == 0) {
            return;
        }
        int count = batchCount();
        if (texelsValid && batches.size() >= count) {
            texelsReady = true;
            return;
        }
        for (int batch = 0; batch < count; batch++) {
            if (batch == batches.size()) {
                batches.add(new Batch());
            }
            Batch storage = batches.get(batch);
            long bytes = (long) batchSize(batch) * instanceStride;
            if (storage.buffer == null || storage.buffer.size() < bytes) {
                GlCompat.requireTextureBufferSize(bytes, 16, "flywheel instances");
                if (storage.buffer != null) {
                    storage.buffer.close();
                }
                storage.buffer = RenderSystem.getDevice().createBuffer(() -> "flywheel instances",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
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
        texelsValid = true;
        texelsReady = true;
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
        batches.clear();
        super.delete();
    }

    private static final class Batch {
        private GpuBuffer buffer;
        private int texture;
    }
}
