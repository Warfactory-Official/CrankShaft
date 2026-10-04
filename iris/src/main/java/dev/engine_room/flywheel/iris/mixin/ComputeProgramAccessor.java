package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ComputeProgram.class)
public interface ComputeProgramAccessor {
    @Accessor("indirectPointer")
    @Nullable FilledIndirectPointer flywheel$indirectPointer();
}
