package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.LOG_2_PAGE_SIZE;
import static dev.engine_room.flywheel.backend.engine.EngineConstants.PAGE_MASK;

public class InstancedInstancer<I extends Instance> extends BaseInstancer<I> {
    private final List<InstancedDraw> draws = new ArrayList<>();
    @Nullable
    private GpuBuffer instanceTexels;
    private int texelTexture;
    private int texelCapacity;
    private boolean texelsReady;
    private boolean texelsValid;

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

    public void resetTexelsReady() {
        texelsReady = false;
    }

    public void prepareInstanceTexels() {
        if (texelsReady) {
            return;
        }
        int count = instanceCount();
        if (count == 0) {
            return;
        }

        boolean needsGrow = instanceTexels == null || texelCapacity < count;
        if (texelsValid && !needsGrow) {
            texelsReady = true;
            return;
        }

        long needBytes = (long) count * instanceStride;
        // updateBuffer migrated every page into the slab: contiguous.
        ByteBuffer instances = MemoryUtil.memByteBuffer(slabBlocks[0], (int) needBytes);

        if (needsGrow) {
            if (instanceTexels != null) {
                instanceTexels.close();
            }
            instanceTexels = RenderSystem.getDevice()
                                         .createBuffer(() -> "flywheel instances",
                                                 GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                                                 needBytes);
            texelCapacity = count;
            if (texelTexture == 0) {
                texelTexture = GlStateManager._genTexture();
            }
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texelTexture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32UI, ((GlBuffer) instanceTexels).handle());
        }
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(instanceTexels.slice(0L, needBytes), instances);
        texelsValid = true;
        texelsReady = true;
    }

    public List<InstancedDraw> draws() {
        return draws;
    }

    public void init() {
    }

    public void updateBuffer() {
        if (prepareUpload() == null || changed.isEmpty()) {
            return;
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

        if (writePos < newSize) {
            changed.set(writePos, newSize);
        }

        changed.clear(newSize, oldSize);

        long[] blocks = slabBlocks;
        for (int scanPos = writePos; (scanPos < oldSize) && (writePos < newSize); scanPos++, writePos++) {
            scanPos = deleted.nextClearBit(scanPos);

            if (scanPos != writePos) {
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
        clear();
        freeGlResources();
    }

    public void addDrawCall(InstancedDraw instancedDraw) {
        draws.add(instancedDraw);
    }
}
