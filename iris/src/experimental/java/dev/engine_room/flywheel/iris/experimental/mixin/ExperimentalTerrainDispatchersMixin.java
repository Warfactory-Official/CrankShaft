package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatcher;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatchers;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.engine.ExperimentalTerrainDispatcher;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TerrainDispatchers.class)
abstract class ExperimentalTerrainDispatchersMixin {
    @Inject(method = "isSupported", at = @At("HEAD"), cancellable = true)
    private static void flywheel$guestSupported(CallbackInfoReturnable<Boolean> cir) {
        if (Iris.isPackInUseQuick()) cir.setReturnValue(flywheel$supported());
    }

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void flywheel$createGuest(CallbackInfoReturnable<TerrainDispatcher> cir) {
        if (Iris.isPackInUseQuick()) {
            if (!flywheel$supported())
                throw new UnsupportedOperationException("Iris terrain requires indirect-count submission");
            cir.setReturnValue(new ExperimentalTerrainDispatcher());
        }
    }

    private static boolean flywheel$supported() {
        return VkContext.isVulkanHost() ? VkCaps.DRAW_INDIRECT_COUNT_NEGOTIATED && VkCaps.DRAW_INDIRECT_FIRST_INSTANCE_NEGOTIATED
                : GlCompat.SUPPORTS_TERRAIN;
    }
}
