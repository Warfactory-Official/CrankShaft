package dev.engine_room.flywheel.backend.engine;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

final class VanillaLightDataCollector extends LightDataCollector {
    private final ChunkProviderClient chunkProvider;
    private final Chunk[] chunks = new Chunk[9];

    VanillaLightDataCollector(World level) {
        super(level);
        this.chunkProvider = (ChunkProviderClient) level.getChunkProvider();
    }

    @Override
    void fillStorages(int sx, int sy, int sz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                chunks[(dx + 1) + (dz + 1) * 3] = chunkProvider.getLoadedChunk(sx + dx, sz + dz);
            }
        }

        for (int i = 0; i < 9; i++) {
            Chunk c = chunks[i];
            if (c == null) continue;
            ExtendedBlockStorage[] arr = c.getBlockStorageArray();
            for (int dy = -1; dy <= 1; dy++) {
                int ySec = sy + dy;
                if (ySec < 0 || ySec >= arr.length) continue;
                storages[i + (dy + 1) * 9] = arr[ySec];
            }
        }
    }
}
