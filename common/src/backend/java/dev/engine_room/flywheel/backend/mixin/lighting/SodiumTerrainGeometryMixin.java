package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryScheduler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SodiumWorldRenderer.class)
abstract class SodiumTerrainGeometryMixin implements TerrainGeometryScheduler {
    @Shadow
    private RenderSectionManager renderSectionManager;

    @Override
    public void lighting$compileForGeometryOcclusion(long section) {
        var manager = renderSectionManager;
        manager.scheduleRebuild(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), true);
    }
}
