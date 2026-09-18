package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlendModeOverride.class)
public interface BlendModeOverrideAccessor {
    @Accessor("blendMode")
    @Nullable BlendMode flywheel$blendMode();
}
