package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.impl.extension.SodiumChunkSectionExtension;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SodiumChunkSection.class)
abstract class SodiumChunkSectionMixin implements SodiumChunkSectionExtension {
    @Shadow @Final private SodiumWorldRenderer renderer;
    @Shadow @Final private ChunkRenderMatrices matrices;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Unique private boolean flywheel$opaqueDrawn;
    @Unique private boolean flywheel$translucentDrawn;

    @Override
    public void flywheel$prepareOpaque(VisualizationManagerImpl manager) {
        flywheel$opaqueDrawn = false;
        var sections = ((SodiumWorldRendererAccessor) renderer).flywheel$getRenderSectionManager();
        if (sections == null) {
            return;
        }
        if (GuestTerrainGate.enabled()) {
            CameraTransform camera = new CameraTransform(x, y, z);
            GuestTerrainGate.setSodiumCamera(camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY,
                    camera.fracZ);
            // 26.3: cancelling Sodium's opaque draw also skips its native uniform update.
            var uniforms = ((SodiumWorldRendererAccessor) renderer).flywheel$uniformBufferManager();
            uniforms.update(matrices, ((SodiumWorldRendererAccessor) renderer).flywheel$lastFogParameters());
            GuestTerrainGate.setSodiumUniforms(uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
        }
        flywheel$opaqueDrawn = manager.renderOpaqueSolidTerrain(matrices, sections);
    }

    @Override
    public void flywheel$prepareShadow(VisualizationManagerImpl manager) {
        flywheel$opaqueDrawn = false;
        var sections = ((SodiumWorldRendererAccessor) renderer).flywheel$getRenderSectionManager();
        if (sections == null) {
            return;
        }
        CameraTransform camera = new CameraTransform(x, y, z);
        GuestTerrainGate.setSodiumCamera(camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY,
                camera.fracZ);
        var uniforms = ((SodiumWorldRendererAccessor) renderer).flywheel$uniformBufferManager();
        uniforms.update(matrices, ((SodiumWorldRendererAccessor) renderer).flywheel$lastFogParameters());
        GuestTerrainGate.setSodiumUniforms(uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
        flywheel$opaqueDrawn = manager.renderShadowTerrain(matrices, sections);
    }

    @Override
    public void flywheel$renderTranslucent(VisualizationManagerImpl manager, GpuSampler sampler) {
        flywheel$translucentDrawn = manager.renderTranslucentOitSodium(renderer, matrices, x, y, z, sampler);
    }

    // 26.3: keep renderGroup and its surrounding Fabric hooks; suppress only the already-owned native draw.
    @WrapOperation(method = "renderGroup",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer;drawChunkLayer(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDDLcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;)V"),
            require = 1)
    private void flywheel$skipOwnedTerrain(SodiumWorldRenderer renderer, RenderPass pass,
                                            ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                            double x, double y, double z, GpuSampler sampler,
                                            @Nullable OitStage stage, Operation<Void> original) {
        if (group == ChunkSectionLayerGroup.OPAQUE && flywheel$opaqueDrawn
                || group == ChunkSectionLayerGroup.TRANSLUCENT && flywheel$translucentDrawn) {
            return;
        }
        original.call(renderer, pass, group, matrices, x, y, z, sampler, stage);
    }
}
