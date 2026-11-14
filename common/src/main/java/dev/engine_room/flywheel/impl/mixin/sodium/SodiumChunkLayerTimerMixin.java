package dev.engine_room.flywheel.impl.mixin.sodium;

#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.oit.OitStage;
#endif
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlGpuTimer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Locale;

/**
 * GPU timer scope per Sodium terrain layer group while the GL timer runs, with or without a backend:
 * {@code sodium/<group>}, {@code sodium/shadow_<group>} in a shaderpack's shadow pass.
 */
@Mixin(SodiumWorldRenderer.class)
abstract class SodiumChunkLayerTimerMixin {
    @WrapMethod(method = "drawChunkLayer")
    private void flywheel$timeLayer(
                                    #if MC >= 26.3
                                    RenderPass renderPass,
                                    #endif
                                    ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                    double x, double y, double z, GpuSampler terrainSampler,
                                    #if MC >= 26.3
                                    @Nullable OitStage stage,
                                    #endif
                                    Operation<Void> original) {
        boolean timed = GlGpuTimer.isEnabled();
        if (timed) {
            GlCompat.pushDebugGroup((GuestTerrainGate.renderingShadow() ? "sodium/shadow_" : "sodium/")
                    + group.name().toLowerCase(Locale.ROOT));
        }
        try {
            original.call(#if MC >= 26.3 renderPass, #endif group, matrices, x, y, z, terrainSampler
                    #if MC >= 26.3 , stage #endif);
        } finally {
            if (timed) GlCompat.popDebugGroup();
        }
    }
}
