package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.TerrainOverlayPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
#endif

@Mixin(DefaultChunkRenderer.class)
abstract class SodiumTerrainOverlayMixin {
    #if MC >= 26.3
    @WrapOperation(method="render",at=@At(value="INVOKE",
            target="Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline flywheel$fusedTerrain(RenderPipeline base,Operation<CompiledRenderPipeline> original,
                                                          @Local(argsOnly=true)TerrainRenderPass terrain) {
        if(TerrainOverlayDraw.isEmpty()||GuestTerrainGate.packInUse())return original.call(base);
        return TerrainOverlayPipelines.nativeCompiled(base,terrain.isTranslucent());
    }
    #else
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void flywheel$fusedTerrain(RenderPass pass, RenderPipeline base, Operation<Void> original,
                                       @Local(argsOnly = true) TerrainRenderPass terrain) {
        original.call(pass, TerrainOverlayDraw.isEmpty() || GuestTerrainGate.packInUse() ? base
                : TerrainOverlayPipelines.nativePipeline(base, terrain.isTranslucent()));
    }
    #endif
}
