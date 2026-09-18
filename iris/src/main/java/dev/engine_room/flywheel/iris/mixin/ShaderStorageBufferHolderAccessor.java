package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderStorageBufferHolder.class)
public interface ShaderStorageBufferHolderAccessor {
    @Accessor("buffers")
    ShaderStorageBuffer[] flywheel$buffers();
}
