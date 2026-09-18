package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SodiumTerrainGeometry {
    private SodiumTerrainGeometry() {
    }

    static void activate(TextureAtlasSprite[] sprites) {
        for (var sprite : sprites) SpriteUtil.INSTANCE.markSpriteActive(sprite);
    }

    static void request(long section) {
        ((TerrainGeometryScheduler) SodiumWorldRenderer.instance()).lighting$compileForGeometryOcclusion(section);
    }

    public static List<TerrainGeometryCache.Capture> capture(ClientLevel level, Collection<BuilderTaskOutput> outputs) {
        if (!TerrainGeometryCache.isActive(level)) return List.of();
        var captures = new ArrayList<TerrainGeometryCache.Capture>();
        for (var output : outputs) {
            if (!(output instanceof ChunkBuildOutput build)) continue;
            var carrier = (SodiumOccluderCapture.Carrier) build;
            var recording = carrier.lighting$occluders();
            carrier.lighting$occluders(null);
            if (recording != null) {
                var capture = recording.complete(level, build);
                if (capture != null) captures.add(capture);
                continue;
            }
            var section = build.section;
            long position = SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());
            if (build.meshes.isEmpty()) {
                var capture = TerrainGeometryCache.begin(level, position);
                if (capture != null) {
                    capture.complete(section, new OcclusionMesh[0]);
                    captures.add(capture);
                }
            } else {
                // Receiver interest can arrive after this job began; rebuild with capture instead of guessing its format.
                TerrainGeometryCache.request(level, position);
            }
        }
        return captures;
    }
}
