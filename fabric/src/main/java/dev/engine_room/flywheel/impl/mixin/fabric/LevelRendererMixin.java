package dev.engine_room.flywheel.impl.mixin.fabric;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class LevelRendererMixin {
	@Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
					shift = At.Shift.BEFORE), require = 1)
	private void flywheel$dispatchFramePlan(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.level instanceof ClientLevel level)) {
			return;
		}
		VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
		if (manager == null) {
			return;
		}
		manager.renderDispatcher()
				.onStartLevelRender(flywheel$buildContext(level));
	}

	@Inject(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V", shift = At.Shift.AFTER), require = 1)
	private void flywheel$afterSolidFeatures(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.level instanceof ClientLevel level)) {
			return;
		}
		VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
		if (manager == null) {
			return;
		}
		RenderContextImpl ctx = flywheel$buildContext(level);
		manager.renderDispatcher()
				.afterEntities(ctx);
		manager.renderDispatcher()
				.beforeCrumbling(ctx, level.destructionProgress());
	}

	private RenderContextImpl flywheel$buildContext(ClientLevel level) {
		return RenderContextImpl.captureCurrent((LevelRenderer) (Object) this, level, RenderSystem.getModelViewStack());
	}
}
