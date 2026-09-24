package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderStorageBuffer.class)
public interface ShaderStorageBufferAccessor {
    @Accessor("id")
    void flywheel$setId(int id);
}
