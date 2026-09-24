package dev.engine_room.flywheel.impl.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Engine's render-time seam into Sodium's terrain draw: OPAQUE -> MDI solid+cutout, TRANSLUCENT -> OIT replay.
 */
@Mixin(SodiumWorldRenderer.class)
public class MixinSodiumWorldRenderer {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Shadow
    private UniformBufferManager uniformBufferManager;

    @Shadow
    private FogParameters lastFogParameters;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$routeTerrainToEngine(ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                               double x, double y, double z, GpuSampler terrainSampler,
                                               CallbackInfo ci) {
        if (!SodiumCompat.isSodiumActive()) {
            return;
        }
        var manager = VisualizationManagerImpl.get(this.level);
        if (manager == null) {
            return;
        }
        if (GuestTerrainGate.enabled()) {
            CameraTransform camera = new CameraTransform(x, y, z);
            GuestTerrainGate.setSodiumCamera(camera.intX, camera.intY, camera.intZ, camera.fracX, camera.fracY,
                    camera.fracZ);
        }

        if (group == ChunkSectionLayerGroup.OPAQUE) {
            var sectionManager = ((SodiumWorldRendererAccessor) (Object) this).flywheel$getRenderSectionManager();
            if (sectionManager == null) {
                return;
            }
            if (GuestTerrainGate.ownsShadowTerrain()) {
                // Sodium's render lists here are the ones its shadow-view setupTerrain just built.
                this.uniformBufferManager.update(matrices, this.lastFogParameters);
                GuestTerrainGate.setSodiumUniforms(this.uniformBufferManager.getUniformBuffer(),
                        this.uniformBufferManager.getSectionTimeInfo());
                if (manager.renderShadowTerrain(matrices, sectionManager)) {
                    ci.cancel();
                    VoxyCompat.renderInPlaceOfCutout(matrices, this.lastFogParameters, x, y, z);
                }
                return;
            }
            // Sodium refreshes these inside renderLayer, which the cancel below skips. The engine's terrain pipeline
            // declares both whenever the gate is on, so they must be current for every draw it makes, not only the
            // ones a pack owns.
            if (GuestTerrainGate.enabled()) {
                this.uniformBufferManager.update(matrices, this.lastFogParameters);
                GuestTerrainGate.setSodiumUniforms(this.uniformBufferManager.getUniformBuffer(),
                        this.uniformBufferManager.getSectionTimeInfo());
            }
            if (manager.renderOpaqueSolidTerrain(matrices, sectionManager)) {
                ci.cancel();
                VoxyCompat.renderInPlaceOfCutout(matrices, this.lastFogParameters, x, y, z);
            }
            return;
        }

        if (group != ChunkSectionLayerGroup.TRANSLUCENT) {
            return;
        }

        if (manager.renderTranslucentOitSodium((SodiumWorldRenderer) (Object) this, matrices, x, y, z,
                terrainSampler)) {
            ci.cancel();
        }
    }
}
