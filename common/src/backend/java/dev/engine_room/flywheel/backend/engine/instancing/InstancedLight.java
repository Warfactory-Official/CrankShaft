package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class InstancedLight {
    private static final long MIN_BYTES = 3L * Integer.BYTES;

    private GpuBuffer lut;
    private GpuBuffer sections;
    private long lutCapacity;
    private long sectionsCapacity;
    private ByteBuffer staging;

    public InstancedLight() {
        lut = createZeroed("flywheel light lut", MIN_BYTES);
        lutCapacity = MIN_BYTES;
        sections = createZeroed("flywheel light sections", MIN_BYTES);
        sectionsCapacity = MIN_BYTES;
    }

    private static GpuBuffer createTexelBuffer(String label, long bytes, @Nullable GpuBuffer previous) {
        GlCompat.requireTextureBufferSize(bytes, Integer.BYTES, label);
        if (bytes > Integer.MAX_VALUE) {
            throw new BackendUnavailableException(label + " exceeds the upload buffer limit");
        }
        if (previous != null) {
            previous.close();
        }
        return RenderSystem.getDevice()
                           .createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                                   bytes);
    }

    private static GpuBuffer createZeroed(String label, long bytes) {
        GpuBuffer buffer = createTexelBuffer(label, bytes, null);
        ByteBuffer zeros = MemoryUtil.memCalloc((int) bytes);
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0L, bytes), zeros);
        MemoryUtil.memFree(zeros);
        return buffer;
    }

    public GpuBuffer lutBuffer() {
        return lut;
    }

    public GpuBuffer sectionsBuffer() {
        return sections;
    }

    public void flush(LightStorage light) {
        if (light.capacity() == 0) {
            return;
        }

        if (light.hasSectionChanges()) {
            long bytes = light.sectionDataBytes();
            if (sections == null || sectionsCapacity < bytes) {
                sections = createTexelBuffer("flywheel light sections", bytes, sections);
                sectionsCapacity = bytes;
            }
            ByteBuffer data = MemoryUtil.memByteBuffer(light.sectionDataPointer(), (int) bytes);
            RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToBuffer(sections.slice(0L, bytes), data);
            light.clearSectionChanges();
        }

        if (light.checkNeedsLutRebuildAndClear()) {
            var words = light.createLut();
            long bytes = (long) words.size() * Integer.BYTES;
            if (lut == null || lutCapacity < bytes) {
                lut = createTexelBuffer("flywheel light lut", bytes, lut);
                lutCapacity = bytes;
            }
            if (staging == null || staging.capacity() < bytes) {
                staging = staging == null ? MemoryUtil.memAlloc((int) bytes)
                        : MemoryUtil.memRealloc(staging, Math.max((int) bytes, staging.capacity() * 2));
            }
            staging.clear().limit((int) bytes);
            for (int i = 0; i < words.size(); i++) staging.putInt(i * Integer.BYTES, words.getInt(i));
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(lut.slice(0L, bytes), staging);
        }
    }

    public void delete() {
        if (staging != null) MemoryUtil.memFree(staging);
        lut.close();
        sections.close();
    }
}
