package dev.engine_room.flywheel.backend.engine;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

final class VanillaLightDataCollector extends LightDataCollector {
    private final LevelAccessor level;
    private final ChunkAccess[] chunks = new ChunkAccess[9];

    VanillaLightDataCollector(LevelAccessor level) {
        super(level);
        this.level = level;
    }

    @Override
    void fillStorages(int sx, int sy, int sz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                chunks[(dx + 1) + (dz + 1) * 3] = level.getChunk(sx + dx, sz + dz, ChunkStatus.FULL, false);
            }
        }

        for (int i = 0; i < 9; i++) {
            ChunkAccess c = chunks[i];
            if (c == null) continue;
            int minSectionY = c.getMinSectionY();
            int maxSectionY = c.getMaxSectionY();
            LevelChunkSection[] arr = c.getSections();
            for (int dy = -1; dy <= 1; dy++) {
                int ySec = sy + dy;
                if (ySec < minSectionY || ySec > maxSectionY) continue;
                int slot = i + (dy + 1) * 9;
                sections[slot] = arr[c.getSectionIndexFromSectionY(ySec)];

                // dx/dz reconstructed from the column slot so the light-engine fetch addresses the
                // correct section (i = (dx + 1) + (dz + 1) * 3).
                int dx = (i % 3) - 1;
                int dz = (i / 3) - 1;
                SectionPos pos = SectionPos.of(sx + dx, ySec, sz + dz);
                blockLight[slot] = blockEngine.getDataLayerData(pos);
                skyLight[slot] = skyEngine.getDataLayerData(pos);
            }
        }
    }
}
