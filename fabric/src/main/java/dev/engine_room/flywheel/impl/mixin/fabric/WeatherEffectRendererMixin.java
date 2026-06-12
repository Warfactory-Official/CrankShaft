package dev.engine_room.flywheel.impl.mixin.fabric;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.buffers.GpuBuffer;

import dev.engine_room.flywheel.impl.FabulousReroute;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;

@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
	@Shadow
	private @Nullable GpuBuffer vertexBuffer;

	@Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void flywheel$suppressVanillaWeather(CallbackInfo ci) {
		if (FabulousReroute.consumeSuppressWeather()) {
			ci.cancel();
		}
	}

	@Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDynamicUniforms()Lnet/minecraft/client/renderer/DynamicUniforms;"),
			cancellable = true)
	private void flywheel$captureWeather(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
		if (FabulousReroute.captureWeather(vertexBuffer, renderState.rainColumns.size(), renderState.snowColumns.size())) {
			ci.cancel();
		}
	}
}
