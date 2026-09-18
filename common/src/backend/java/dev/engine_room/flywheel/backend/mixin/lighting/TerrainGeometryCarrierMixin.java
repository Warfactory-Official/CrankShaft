package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache.Capture;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCarrier;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({SectionCompiler.Results.class, CompiledSectionMesh.class})
abstract class TerrainGeometryCarrierMixin implements TerrainGeometryCarrier {
    @Unique
    private @Nullable Capture lighting$terrainCapture;

    @Override
    public @Nullable Capture lighting$terrainGeometry() {
        return lighting$terrainCapture;
    }

    @Override
    public void lighting$terrainGeometry(@Nullable Capture capture) {
        lighting$terrainCapture = capture;
    }
}
