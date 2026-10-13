package dev.engine_room.flywheel.impl.mixin.sodium;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.NativeOitPipelines;
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ShaderChunkRenderer.class)
abstract class ShaderChunkRendererWarmupMixin {
    @Shadow @Final private static Map<TerrainRenderPass, OitPipelineSet> oitPrograms;
    @Shadow protected abstract RenderPipeline compileProgram(TerrainRenderPass pass, @Nullable OitStage stage);

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void flywheel$registerNativeDeclarations(CallbackInfo ci) {
        NativeOitWarmup.registerDeclarations(() -> oitPrograms.values().forEach(NativeOitPipelines::register));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$warmNativeTerrain(ChunkVertexType vertexType, CallbackInfo ci) {
        // 26.3: create declarations with the owning renderer's actual vertex format before its first pass.
        compileProgram(DefaultTerrainRenderPasses.TRANSLUCENT, OitStage.DEPTH_BOUNDS);
        NativeOitWarmup.configurationChanged();
    }
}
