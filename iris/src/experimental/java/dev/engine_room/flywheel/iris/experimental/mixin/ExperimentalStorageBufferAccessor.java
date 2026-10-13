package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderStorageBuffer.class)
public interface ExperimentalStorageBufferAccessor {
    @Accessor("buffer")
    void flywheel$buffer(GpuBuffer buffer);
}
