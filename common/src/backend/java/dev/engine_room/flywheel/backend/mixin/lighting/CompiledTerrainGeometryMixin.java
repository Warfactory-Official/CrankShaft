package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCarrier;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryDecoder;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledTerrainGeometryMixin {
    // All compiler-return hooks have run; the CPU buffers are still alive here, before GPU upload.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lighting$capture(TranslucencyPointOfView view, SectionCompiler.Results results, CallbackInfo ci) {
        var capture = ((TerrainGeometryCarrier) (Object) results).lighting$terrainGeometry();
        if (capture != null) {
            capture.complete(this, TerrainGeometryDecoder.vanilla(results));
            ((TerrainGeometryCarrier) this).lighting$terrainGeometry(capture);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lighting$discard(CallbackInfo ci) {
        var capture = ((TerrainGeometryCarrier) this).lighting$terrainGeometry();
        if (capture != null) capture.close();
    }
}
