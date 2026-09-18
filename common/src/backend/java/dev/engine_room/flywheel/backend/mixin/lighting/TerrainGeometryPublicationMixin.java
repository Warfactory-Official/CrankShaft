package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCarrier;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class TerrainGeometryPublicationMixin {
    // Publication follows completed uploads, not merely a worker finishing a possibly cancelled build.
    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void lighting$publish(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> ci) {
        if (mesh instanceof TerrainGeometryCarrier carrier) {
            var capture = carrier.lighting$terrainGeometry();
            if (capture != null && capture.section == ((SectionRenderDispatcher.RenderSection) (Object) this).getSectionNode())
                capture.publish();
        }
    }
}
