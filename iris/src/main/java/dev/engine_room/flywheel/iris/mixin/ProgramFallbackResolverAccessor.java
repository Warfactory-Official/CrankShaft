package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ProgramFallbackResolver.class)
public interface ProgramFallbackResolverAccessor {
    @Accessor("programs")
    ProgramSet flywheel$programs();
}
