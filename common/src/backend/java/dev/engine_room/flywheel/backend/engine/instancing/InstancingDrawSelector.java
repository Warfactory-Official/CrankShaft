package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.compat.backend.UniformData;
import dev.engine_room.flywheel.compat.backend.UniformStorage;
import dev.engine_room.flywheel.compat.backend.UniformStorageOps;

import java.nio.ByteBuffer;

public final class InstancingDrawSelector {
    public static final String NAME = "_FlwInstanceSelector";

    private final UniformStorage<Selector> uniforms = UniformStorageOps.create("flywheel:instance_selector", 16, 64);

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

    private record Selector(int localFirstInstance, int globalFirstInstance, int baseVertex) implements UniformData {
        @Override
        public void write(ByteBuffer buffer) {
            buffer.putInt(localFirstInstance).putInt(globalFirstInstance).putInt(baseVertex).putInt(0);
        }
    }
}
