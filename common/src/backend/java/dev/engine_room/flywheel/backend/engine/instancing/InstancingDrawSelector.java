package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.minecraft.client.renderer.DynamicUniformStorage;

import java.nio.ByteBuffer;

public final class InstancingDrawSelector {
    public static final String NAME = "_FlwInstanceSelector";

    private final DynamicUniformStorage<Selector> uniforms = new DynamicUniformStorage<>("flywheel:instance_selector", 16, 64);

    public static BindGroupLayout.Builder addTo(BindGroupLayout.Builder builder) {
        return GlCompat.USE_INSTANCING_SELECTOR ? builder.withUniform(NAME, UniformType.UNIFORM_BUFFER) : builder;
    }

    public void beginFrame() {
        uniforms.endFrame();
    }

    public GpuBufferSlice slice(int localFirstInstance, int globalFirstInstance, int baseVertex) {
        return uniforms.writeUniform(new Selector(localFirstInstance, globalFirstInstance, baseVertex));
    }

    public void delete() {
        uniforms.close();
    }

    private record Selector(int localFirstInstance, int globalFirstInstance, int baseVertex)
            implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buffer) {
            buffer.putInt(localFirstInstance).putInt(globalFirstInstance).putInt(baseVertex).putInt(0);
        }
    }
}
