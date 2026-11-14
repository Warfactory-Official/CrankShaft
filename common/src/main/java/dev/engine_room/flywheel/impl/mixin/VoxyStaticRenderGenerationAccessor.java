package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderGenerationService.class)
abstract class VoxyStaticRenderGenerationAccessor implements VoxyStaticVisuals.GenerationAccess {
    @Shadow
    @Final
    private WorldEngine world;

    @Override
    public WorldEngine flywheel$world() {
        return world;
    }
}

#endif
