package dev.engine_room.flywheel.backend.engine;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProvider;
import net.minecraft.world.World;

final class CubicLightDataCollector extends LightDataCollector {
    private final ICubeProvider cubeCache;

    CubicLightDataCollector(World level, ICubeProvider cubeCache) {
        super(level);
        this.cubeCache = cubeCache;
    }

    @Override
    void fillStorages(int sx, int sy, int sz) {
        for (int dy = -1; dy <= 1; dy++) {
            int yBase = (dy + 1) * 9;
            int cy = sy + dy;
            for (int dz = -1; dz <= 1; dz++) {
                int zBase = (dz + 1) * 3;
                int cz = sz + dz;
                for (int dx = -1; dx <= 1; dx++) {
                    ICube cube = cubeCache.getLoadedCube(sx + dx, cy, cz);
                    if (cube != null) {
                        storages[(dx + 1) + zBase + yBase] = cube.getStorage();
                    }
                }
            }
        }
    }
}
