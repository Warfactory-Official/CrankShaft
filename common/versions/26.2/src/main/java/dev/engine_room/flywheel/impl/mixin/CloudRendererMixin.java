package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.FabulousReroute;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
    @Shadow
    private int quadCount;
    @Shadow
    @Final
    private MappableRingBuffer ubo;
    @Shadow
    private @Nullable MappableRingBuffer utb;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flywheel$suppressVanillaClouds(CallbackInfo ci) {
        if (FabulousReroute.consumeSuppressClouds()) {
            ci.cancel();
        }
    }

    // The first op after the CloudInfo UBO write: everything the OIT replay needs is prepared.
    @Inject(method = "render",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDynamicUniforms()Lnet/minecraft/client/renderer/DynamicUniforms;"),
            cancellable = true)
    private void flywheel$captureClouds(int color, CloudStatus cloudStatus, float bottomY, int range,
                                        Vec3 cameraPosition, long gameTime, float partialTicks, CallbackInfo ci) {
        if (FabulousReroute.captureClouds(ubo.currentBuffer(), utb.currentBuffer(), quadCount,
                cloudStatus == CloudStatus.FANCY)) {
            ci.cancel();
        }
    }
}
