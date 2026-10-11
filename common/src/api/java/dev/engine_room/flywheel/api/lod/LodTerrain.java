package dev.engine_room.flywheel.api.lod;

import dev.engine_room.flywheel.api.internal.FlwApiLink;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Terrain a level-of-detail mod (Distant Horizons, Voxy) draws past vanilla's render distance. While it draws, the
 * main projection reaches it and its depth occludes later draws like terrain. Client thread.
 */
public final class LodTerrain {
    private LodTerrain() {
    }

    /**
     * Horizontal block radius of the LoD terrain drawn now; {@code 0} when no supported LoD mod draws.
     */
    public static int distance() {
        return FlwApiLink.INSTANCE.lodTerrainDistance();
    }

    /**
     * {@code listener} receives the new {@link #distance()} when a rendered frame observes it changed, including the
     * first frame after startup.
     */
    public static void onChange(IntConsumer listener) {
        FlwApiLink.INSTANCE.onLodTerrainChange(listener);
    }

    /**
     * Heights of the LoD terrain the client's current level holds over a block square, read off-thread from the LoD
     * mod's storage; completes with {@code tops == null} when no supported LoD mod draws.
     */
    public static CompletableFuture<LodSurface> surface(Heightmap.Types type, int minX, int minZ, int size) {
        return FlwApiLink.INSTANCE.lodTerrainSurface(type, minX, minZ, size);
    }
}
