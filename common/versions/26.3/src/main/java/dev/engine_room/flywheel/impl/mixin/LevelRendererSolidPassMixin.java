package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.event.LevelRenderPassScope;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives Sodium terrain and engine solid features closed-pass intervals, preserving the outer resource local and
 * the solid method's argument. NeoForge's stage events consume that same updated argument.
 */
@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class LevelRendererSolidPassMixin {
    @Unique
    private RenderPass flywheel$solidPass;

    @WrapOperation(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1)
    private void flywheel$scopeSolidPass(LevelRenderer renderer, ChunkSectionsToRender chunks,
                                         FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
                                         Operation<Void> original, @Local LocalRef<RenderPass> callerPass) {
        Minecraft minecraft = Minecraft.getInstance();
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(minecraft.level);
        if (manager == null && !DistantHorizonsCompat.ACTIVE) {
            original.call(renderer, chunks, frame, pass);
            return;
        }
        flywheel$solidPass = pass;
        try {
            original.call(renderer, chunks, frame, pass);
            if (manager != null) {
                flywheel$solidPass = LevelRenderPassScope.afterSolid(flywheel$solidPass, manager,
                        RenderContextImpl.captureCurrent(renderer, minecraft.level, RenderSystem.getModelViewStack()));
            }
        } finally {
            callerPass.set(flywheel$solidPass);
            flywheel$solidPass = null;
        }
    }

    @WrapOperation(method = "executeSolid",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1)
    private void flywheel$captureOpaqueDepth(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
                                             Operation<Void> original,
                                             @Local(argsOnly = true) LocalRef<RenderPass> methodPass) {
        if (DistantHorizonsCompat.ACTIVE) {
            // 26.3: texture copies require a closed frontend pass.
            RenderPassDescriptor continuation = LevelRenderPassScope.suspend(pass);
            LodCompat.captureTerrainDepth(0);
            pass = LevelRenderPassScope.resume(continuation);
            methodPass.set(pass);
            flywheel$solidPass = pass;
        }
        original.call(frame, pass);
    }

    @WrapOperation(method = "executeSolid",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"),
            require = 1)
    private void flywheel$prepareSodiumTerrain(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group,
                                         RenderPass pass, GpuSampler sampler, GpuTextureView atlas,
                                         boolean wireframe, Operation<Void> original,
                                         @Local(argsOnly = true) LocalRef<RenderPass> methodPass) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (!SodiumCompat.ACTIVE || manager == null) {
            original.call(chunks, group, pass, sampler, atlas, wireframe);
            return;
        }
        LevelRenderPassScope.requireSupported(manager);
        if (!GuestTerrainGate.enabled()) {
            // 26.3: mode OFF only withdraws the CPU registry; it needs no extra pass boundary.
            SodiumCompat.renderOpaqueTerrain(manager, chunks);
            original.call(chunks, group, pass, sampler, atlas, wireframe);
            return;
        }
        RenderPassDescriptor continuation = LevelRenderPassScope.suspend(pass);
        SodiumCompat.renderOpaqueTerrain(manager, chunks);
        RenderPass resumed = LevelRenderPassScope.resume(continuation);
        methodPass.set(resumed);
        flywheel$solidPass = resumed;
        // 26.3: dispatch the carrier and surrounding mod hooks even when its native draw is already owned.
        original.call(chunks, group, resumed, sampler, atlas, wireframe);
    }
}
