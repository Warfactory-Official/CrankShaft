package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.TerrainOverlayPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

@Mixin({ChunkSectionsToRender.DrawSeparate.class,ChunkSectionsToRender.DrawIndirect.class})
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Mixin(ChunkSectionsToRender.class)
#endif
abstract class ChunkSectionsTerrainOverlayMixin {
    #if MC >= 26.3
    @WrapOperation(method="render",at=@At(value="INVOKE",
            target="Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline flywheel$fusedTerrain(RenderPipeline base,Operation<CompiledRenderPipeline> original,
                                                          @Local(argsOnly=true)ChunkSectionLayer layer) {
        if(TerrainOverlayDraw.isEmpty())return original.call(base);
        return TerrainOverlayPipelines.nativeCompiled(base,layer.translucent());
    }
    #else
    @WrapOperation(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void flywheel$fusedTerrain(RenderPass pass, RenderPipeline base, Operation<Void> original,
                                       @Local(argsOnly = true) ChunkSectionLayerGroup group) {
        boolean opaque = group == ChunkSectionLayerGroup.OPAQUE;
        original.call(pass, TerrainOverlayPipelines.nativePipeline(base, !opaque));
    }

    // TRANSLUCENT groups mix vertex buffers.
    @WrapOperation(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"))
    private <T> void flywheel$receiverRuns(RenderPass pass, Collection<RenderPass.Draw<T>> draws,
                                           @Nullable GpuBuffer indices,
                                           @Nullable IndexType type, Collection<String> uniforms, T argument,
                                           Operation<Void> original) {
        if (TerrainOverlayDraw.isEmpty()) {
            original.call(pass, draws, indices, type, uniforms, argument);
            return;
        }
        TerrainReceiverBuffers.forEachRun(
                draws instanceof List<RenderPass.Draw<T>> ordered ? ordered : new ArrayList<>(draws),
                (receivers, run) -> {
                    pass.setUniform("TerrainReceiverIds", receivers);
                    original.call(pass, run, indices, type, uniforms, argument);
                });
    }
    #endif
}
