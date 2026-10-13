package dev.engine_room.flywheel.iris.mixin;

#if MC >= 26.3
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.jspecify.annotations.Nullable;
#endif
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.iris.compile.GuestSsbos;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

#if MC >= 26.3
import java.util.Collection;
#endif

@Mixin(TerrainDrawDispatcher.class)
abstract class TerrainDrawDispatcherMixin {
    #if MC >= 26.3
    // 26.3: the owned draw precedes Iris's supplied-pass wrapper. Native OPAQUE groups solid and cutout
    // under one phase.
    @WrapMethod(method = "drawOpaqueSolid")
    private boolean flywheel$scopeOwnedTerrainPhase(ChunkRenderMatrices matrices, RenderSectionManager manager,
                                                     @Nullable Collection<RenderRegion> regions,
                                                     Operation<Boolean> original) {
        if (!GuestTerrainGate.enabled() || !Iris.isPackInUseQuick()) return original.call(matrices, manager, regions);
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        WorldRenderingPhase previous = ((IrisRenderingPipelineAccessor) pipeline).flywheel$overridePhase();
        if (previous != null) return original.call(matrices, manager, regions);
        pipeline.setOverridePhase(WorldRenderingPhase.fromTerrainRenderType(ChunkSectionLayerGroup.OPAQUE));
        try {
            return original.call(matrices, manager, regions);
        } finally {
            pipeline.setOverridePhase(previous);
        }
    }
    #endif

    @Unique
    private static void flywheel$restorePackBuffers() {
        if (GuestTerrainGate.enabled()
                && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            GuestSsbos.restore(pipeline);
        }
    }

    @Inject(method = {"drawOpaqueSolid", "drawShadowTerrain"}, at = @At("HEAD"), require = 1)
    private void flywheel$prepareGuestDraw(CallbackInfoReturnable<Boolean> cir) {
        GuestTerrainGate.packActive = GuestTerrainGate.enabled() && Iris.isPackInUseQuick();
    }

    @Inject(method = "prepareResidentTranslucent", at = @At("HEAD"), require = 1)
    private void flywheel$prepareGuestTranslucent(CallbackInfo ci) {
        GuestTerrainGate.packActive = GuestTerrainGate.enabled() && Iris.isPackInUseQuick();
    }

    // Registry uploads can bind scatter SSBOs even when no terrain draw follows. Restore at the whole operation's
    // boundary, not a program's clear callback or a later instance draw that might never happen.
    @Inject(method = {"drawOpaqueSolid", "drawShadowTerrain"}, at = @At("RETURN"), require = 1)
    private void flywheel$restoreAfterDraw(CallbackInfoReturnable<Boolean> cir) {
        flywheel$restorePackBuffers();
    }

    @Inject(method = "prepareResidentTranslucent", at = @At("RETURN"), require = 1)
    private void flywheel$restoreAfterPreparation(CallbackInfo ci) {
        flywheel$restorePackBuffers();
    }
}
