package dev.engine_room.flywheel.impl.mixin.fabric;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	public ClientLevel level;

	@Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"), require = 1)
	private void flywheel$onSetLevel(CallbackInfo ci) {
		if (level != null) {
			VisualizationEventHandler.onLevelUnload(level);
		}
	}

	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"), require = 1)
	private void flywheel$onDisconnect(CallbackInfo ci) {
		if (level != null) {
			VisualizationEventHandler.onLevelUnload(level);
		}
	}
}
