package dev.engine_room.flywheel.impl.mixin.visualmanage;

import net.minecraft.client.renderer.entity.EndermanRenderer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ThreadLocalRandom;

@Mixin(EndermanRenderer.class)
abstract class EndermanRendererMixin {
    // 26.2: The renderer's RNG is shared, while worker captures may call this offset concurrently.
    @Redirect(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/EndermanRenderState;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextGaussian()D"),
            require = 2)
    private double flywheel$threadSafeRenderRandom(RandomSource original) {
        return ThreadLocalRandom.current().nextGaussian();
    }
}
