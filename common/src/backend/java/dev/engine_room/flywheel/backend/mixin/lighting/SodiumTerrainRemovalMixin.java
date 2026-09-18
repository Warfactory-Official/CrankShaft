package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSection.class)
abstract class SodiumTerrainRemovalMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void lighting$removeTerrain(CallbackInfo ci) {
        var section = (RenderSection) (Object) this;
        TerrainGeometryCache.remove(section,
                SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }
}
