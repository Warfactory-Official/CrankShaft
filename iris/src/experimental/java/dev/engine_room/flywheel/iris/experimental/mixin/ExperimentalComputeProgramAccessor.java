package dev.engine_room.flywheel.iris.experimental.mixin;

import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ComputeProgram.class)
public interface ExperimentalComputeProgramAccessor {
    @Accessor("indirect")
    FilledIndirectPointer flywheel$indirect();
}
