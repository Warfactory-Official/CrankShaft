package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class InstancedLight {
    private static final long MIN_BYTES = 2L * Integer.BYTES;

    private GpuBuffer lut;
    private GpuBuffer sections;
    private long lutCapacity;
    private long sectionsCapacity;

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

        if (light.checkNeedsLutRebuildAndClear()) {
            IntArrayList lutData = light.createLut();
            long bytes = (long) lutData.size() * Integer.BYTES;
            if (lut == null || lutCapacity < bytes) {
                if (lut != null) {
                    lut.close();
                }
                lut = createTexelBuffer("flywheel light lut", bytes);
                lutCapacity = bytes;
            }
            ByteBuffer staging = MemoryUtil.memAlloc((int) bytes);
            for (int i = 0; i < lutData.size(); i++) {
                staging.putInt(i * Integer.BYTES, lutData.getInt(i));
            }
            RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToBuffer(lut.slice(0L, bytes), staging);
            MemoryUtil.memFree(staging);
        }
    }

    public void delete() {
        lut.close();
        sections.close();
    }
}
