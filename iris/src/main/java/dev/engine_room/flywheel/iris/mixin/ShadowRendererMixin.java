package dev.engine_room.flywheel.iris.mixin;

#if MC >= 26.3
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.event.LevelRenderPassScope;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
#endif
import dev.engine_room.flywheel.iris.engine.GuestShadows;
import net.irisshaders.iris.shadows.ShadowRenderer;
#if MC >= 26.3
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translucent guest casters after the pre-translucent depth copy, as translucent terrain: Iris's shadow render callback
 * runs before it, where they would land in {@code shadowtex1} as opaque casters.
 */
@Mixin(ShadowRenderer.class)
abstract class ShadowRendererMixin {
    #if MC >= 26.3
    @WrapOperation(method = "renderShadows",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
                    ordinal = 0),
            require = 1)
    private void flywheel$shadowTerrain(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group,
                                        RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe,
                                        Operation<Void> original, @Local LocalRef<RenderPass> callerPass) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager == null || !GuestTerrainGate.ownsShadowTerrain()) {
            original.call(chunks, group, pass, sampler, atlas, wireframe);
            return;
        }
        RenderPassDescriptor continuation = LevelRenderPassScope.suspend(pass);
        SodiumCompat.renderShadowTerrain(manager, chunks);
        RenderPass resumed = LevelRenderPassScope.resume(continuation);
        callerPass.set(resumed);
        // Keep the carrier's mod hooks while suppressing only its already-owned opaque draw.
        original.call(chunks, group, resumed, sampler, atlas, wireframe);
    }
    #endif

    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V",
            shift = At.Shift.AFTER))
    private void flywheel$guestTranslucentShadows(CallbackInfo ci) {
        GuestShadows.afterPreTranslucentDepth();
    }
}
