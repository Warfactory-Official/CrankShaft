package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.impl.LevelFrame;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.event.LevelRenderPassScope;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Preserves classic transparency ordering while yielding the caller-owned pass at the translucent terrain seam.
 * The scoped pass propagates the replacement from the inner method's argument to the outer resource local.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFabulousLayersMixin {
    @Unique
    private RenderPass flywheel$classicPass;

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"),
            require = 1)
    private FeatureRenderDispatcher.PreparedFrame flywheel$captureLevelFrame(
            FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
            Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        LevelFrame.submits(storage);
        return original.call(dispatcher, storage);
    }

    @WrapOperation(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1)
    private void flywheel$scopeClassicPass(LevelRenderer renderer, ChunkSectionsToRender chunks,
                                           FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
                                           Operation<Void> original, @Local LocalRef<RenderPass> callerPass) {
        flywheel$classicPass = pass;
        try {
            original.call(renderer, chunks, frame, pass);
        } finally {
            callerPass.set(flywheel$classicPass);
            flywheel$classicPass = null;
        }
    }

    @WrapOperation(method = "executeClassicTransparency",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"),
            require = 1)
    private void flywheel$runOitAtTranslucentTerrain(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group,
                                               RenderPass pass, GpuSampler sampler, GpuTextureView atlas,
                                               boolean wireframe, Operation<Void> original,
                                               @Local(argsOnly = true) LocalRef<RenderPass> methodPass) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager == null && !DistantHorizonsCompat.ACTIVE) {
            original.call(chunks, group, pass, sampler, atlas, wireframe);
            return;
        }
        RenderPassDescriptor continuation = LevelRenderPassScope.suspend(pass);
        LodCompat.captureTerrainDepth(1);
        boolean sodium = SodiumCompat.ACTIVE;
        boolean composited = false;
        if (manager != null) {
            if (sodium) {
                SodiumCompat.renderTranslucentTerrain(manager, chunks, sampler);
            } else {
                composited = manager.renderTranslucentOit(chunks, sampler);
            }
        }
        RenderPass resumed = LevelRenderPassScope.resume(continuation);
        methodPass.set(resumed);
        flywheel$classicPass = resumed;
        if (sodium || !composited) {
            original.call(chunks, group, resumed, sampler, atlas, wireframe);
        }
        if (DistantHorizonsCompat.ACTIVE) {
            resumed.close();
            LodCompat.captureTerrainDepth(2);
            resumed = LevelRenderPassScope.resume(continuation);
            methodPass.set(resumed);
            flywheel$classicPass = resumed;
        }
    }
}
