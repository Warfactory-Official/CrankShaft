package dev.engine_room.flywheel.impl.mixin;

import dev.lambdaurora.lambdynlights.LambDynLights;
import dev.lambdaurora.lambdynlights.engine.source.DynamicLightSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(LambDynLights.class)
public interface LambDynLightsAccessor {
    @Accessor("dynamicLightSources")
    Set<DynamicLightSource> flywheel$dynamicLightSources();
}
