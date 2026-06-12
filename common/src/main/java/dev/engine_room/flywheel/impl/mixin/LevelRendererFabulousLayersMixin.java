package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.engine_room.flywheel.backend.engine.FabulousLayerTargets;
import dev.engine_room.flywheel.impl.FabulousReroute;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the item-entity/particle layer getters to flywheel-owned targets while the translucent window is open.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFabulousLayersMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"), require = 1)
    private FeatureRenderDispatcher.PreparedFrame flw$captureLevelFrame(FeatureRenderDispatcher dispatcher,
                                                                        SubmitNodeStorage storage,
                                                                        Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        FabulousReroute.levelSubmits(storage);
        return original.call(dispatcher, storage);
    }

    @Inject(method = "itemEntityTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$redirectItemEntityTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = FabulousLayerTargets.redirectItemEntity();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }

    @Inject(method = "particlesTarget", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$redirectParticlesTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = FabulousLayerTargets.redirectParticles();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
