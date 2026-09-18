package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.engine.LightStorage;
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

    private static GpuBuffer createTexelBuffer(String label, long bytes) {
        return RenderSystem.getDevice()
                           .createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                                   bytes);
    }

    private static GpuBuffer createZeroed(String label, long bytes) {
        GpuBuffer buffer = createTexelBuffer(label, bytes);
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
                if (sections != null) {
                    sections.close();
                }
                sections = createTexelBuffer("flywheel light sections", bytes);
                sectionsCapacity = bytes;
            }
            ByteBuffer data = MemoryUtil.memByteBuffer(light.sectionDataPointer(), (int) bytes);
            RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToBuffer(sections.slice(0L, bytes), data);
            light.clearSectionChanges();
        }

        var update = light.pollLutUpdates();
        if (update != null) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int span = 0; span < update.count(); span++) {
                long bytes = (long) update.length(span) * Integer.BYTES;
                long capacity = (long) update.totalWords() * Integer.BYTES;
                if (lut == null || lutCapacity < capacity) {
                    assert update.offset(span) == 0;
                    if (lut != null) lut.close();
                    lut = createTexelBuffer("flywheel light lut", capacity);
                    lutCapacity = capacity;
                }
                if (staging == null || staging.capacity() < bytes) {
                    staging = staging == null ? MemoryUtil.memAlloc((int) bytes)
                            : MemoryUtil.memRealloc(staging, Math.max((int) bytes, staging.capacity() * 2));
                }
                staging.clear().limit((int) bytes);
                int source = update.source(span);
                for (int i = 0; i < update.length(span); i++) {
                    staging.putInt(i * Integer.BYTES, update.word(source + i));
                }
                encoder.writeToBuffer(lut.slice((long) update.offset(span) * Integer.BYTES, bytes), staging);
            }
        }
    }

    public void delete() {
        if (staging != null) MemoryUtil.memFree(staging);
        lut.close();
        sections.close();
    }
}
