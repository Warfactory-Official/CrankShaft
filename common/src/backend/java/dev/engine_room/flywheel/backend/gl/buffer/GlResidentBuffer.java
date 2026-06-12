package dev.engine_room.flywheel.backend.gl.buffer;

import dev.engine_room.flywheel.backend.gl.GlObject;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.NVShaderBufferLoad;

/**
 * NV-bindless-resident GPU buffer with a stable device address. Storage is immutable -- a grow reallocates
 * (new GL name + device address) and invalidates the cached address.
 */
public class GlResidentBuffer extends GlObject {
    private long capacity;
    private long deviceAddress;
    private boolean addressValid;
    private int addressGeneration;

    public GlResidentBuffer() {
        handle(GL45.glCreateBuffers());
    }

    public boolean ensureCapacity(long bytes) {
        if (bytes <= capacity) {
            return false;
        }
        if (capacity > 0) {
            deleteInternal(handle());
            invalidateHandle();
            handle(GL45.glCreateBuffers());
        }
        GL45.glNamedBufferStorage(handle(), bytes, GL45.GL_DYNAMIC_STORAGE_BIT);
        FlwMemoryTracker._allocGpuMemory(bytes);
        capacity = bytes;
        addressValid = false;
        addressGeneration++;
        return true;
    }

    public void uploadSpan(long offset, MemoryBlock block) {
        uploadSpan(offset, block.ptr(), block.size());
    }

    public void uploadSpan(long offset, long ptr, long size) {
        Buffer.IMPL.subData(handle(), offset, size, ptr);
    }

    public long deviceAddress() {
        if (!addressValid) {
            int h = handle();
            if (!NVShaderBufferLoad.glIsNamedBufferResidentNV(h)) {
                // READ_WRITE: the cull chain STORES through these pointers; NV_shader_buffer_store makes
                // stores to a READ_ONLY-resident buffer undefined -- on NVIDIA they never reached the
                // storage that readbacks sample.
                NVShaderBufferLoad.glMakeNamedBufferResidentNV(h, GL15.GL_READ_WRITE);
            }
            deviceAddress = NVShaderBufferLoad.glGetNamedBufferParameterui64NV(h,
                    NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
            addressValid = true;
        }
        return deviceAddress;
    }

    /**
     * Monotonic generation counter -- a scene UBO built against an older value is stale.
     */
    public int addressGeneration() {
        return addressGeneration;
    }

    public long capacity() {
        return capacity;
    }

    @Override
    protected void deleteInternal(int handle) {
        GL15.glDeleteBuffers(handle);
        GlStateTracker._onBufferDeleted(handle);
        FlwMemoryTracker._freeGpuMemory(capacity);
    }
}
