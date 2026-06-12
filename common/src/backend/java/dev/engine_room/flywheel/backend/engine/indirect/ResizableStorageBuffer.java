package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.gl.GlObject;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.NVShaderBufferLoad;

import static org.lwjgl.opengl.GL45.*;

public class ResizableStorageBuffer extends GlObject {
    private long capacity = 0;
    private long deviceAddress;
    private boolean addressValid;
    private int addressGeneration;

    public ResizableStorageBuffer() {
        handle(glCreateBuffers());
    }

    public long capacity() {
        return capacity;
    }

    public void ensureCapacity(long capacity) {
        FlwMemoryTracker._freeGpuMemory(this.capacity);

        if (this.capacity > 0) {
            int oldHandle = handle();
            int newHandle = glCreateBuffers();

            glNamedBufferStorage(newHandle, capacity, 0);

            glCopyNamedBufferSubData(oldHandle, newHandle, 0, 0, this.capacity);

            deleteInternal(oldHandle);

            handle(newHandle);
            addressValid = false;
            addressGeneration++;
        } else {
            glNamedBufferStorage(handle(), capacity, 0);
        }
        this.capacity = capacity;
        FlwMemoryTracker._allocGpuMemory(this.capacity);
    }

    public long deviceAddress() {
        if (!addressValid) {
            int h = handle();
            if (!NVShaderBufferLoad.glIsNamedBufferResidentNV(h)) {
                NVShaderBufferLoad.glMakeNamedBufferResidentNV(h, GL15.GL_READ_WRITE);
            }
            deviceAddress = NVShaderBufferLoad.glGetNamedBufferParameterui64NV(h,
                    NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
            addressValid = true;
        }
        return deviceAddress;
    }

    public int addressGeneration() {
        return addressGeneration;
    }

    @Override
    protected void deleteInternal(int handle) {
        glDeleteBuffers(handle);
        GlStateTracker._onBufferDeleted(handle);
    }

    @Override
    public void delete() {
        super.delete();
        FlwMemoryTracker._freeGpuMemory(capacity);
        capacity = 0;
    }
}
