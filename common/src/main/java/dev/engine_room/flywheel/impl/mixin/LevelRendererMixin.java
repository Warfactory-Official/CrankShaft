package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 1001) // Higher priority to go after Sodium
abstract class LevelRendererMixin {
    #if MC >= 26.3
    @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
    #else
    @Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
    #endif
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
                    shift = At.Shift.BEFORE), require = 1)
    private void flywheel$dispatchFramePlan(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            return;
        }
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager != null) {
            manager.renderDispatcher().onStartLevelRender(flywheel$buildContext(level));
        }
    }

    private RenderContextImpl flywheel$buildContext(ClientLevel level) {
        return RenderContextImpl.captureCurrent((LevelRenderer) (Object) this, level, RenderSystem.getModelViewStack());
    }
}
