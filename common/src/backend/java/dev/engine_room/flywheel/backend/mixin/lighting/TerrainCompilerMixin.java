package dev.engine_room.flywheel.backend.mixin.lighting;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCarrier;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
abstract class TerrainCompilerMixin {
    // NeoForge adds a compile overload. Tag its allocated result before meshing, not the delegating bridge.
    @ModifyExpressionValue(method = "compile*", at = @At(value = "NEW", target = "net/minecraft/client/renderer/chunk/SectionCompiler$Results"))
    private SectionCompiler.Results lighting$trackTerrain(SectionCompiler.Results result,
                                                          @Local(argsOnly = true) SectionPos position,
                                                          @Local(argsOnly = true) RenderSectionRegion region) {
        ((TerrainGeometryCarrier) (Object) result).lighting$terrainGeometry(
                TerrainGeometryCache.begin(region.level, position.asLong()));
        return result;
    }
}
