package dev.engine_room.flywheel.api.lod;

import dev.engine_room.flywheel.api.internal.FlwApiLink;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Configured reach and stored terrain of supported level-of-detail renderers (Distant Horizons, Voxy). Client thread.
 */
public final class LodTerrain {
    private LodTerrain() {
    }

    /**
     * Configured horizontal block radius of an enabled supported LoD renderer; {@code 0} when none is enabled.
     * Can be nonzero outside an active world and does not measure submitted terrain coverage.
     */
    public static int distance() {
        return FlwApiLink.INSTANCE.lodTerrainDistance();
    }

    /**
     * Registers {@code listener} for global {@link #distance()} changes observed by rendered frames, including the
     * first startup observation for listeners already registered. Late registration does not replay the current
     * value; read {@link #distance()} for initial state.
     */
    public static void onChange(IntConsumer listener) {
        FlwApiLink.INSTANCE.onLodTerrainChange(listener);
    }

    /**
     * Heights held by the LoD provider for the active client level over a block square, read off-thread from its
     * storage. Requires an active level; completes with {@code tops == null} when the level or provider is
     * unavailable. Missing columns are represented by {@link LodSurface#UNKNOWN}.
     */
    public static CompletableFuture<LodSurface> surface(Heightmap.Types type, int minX, int minZ, int size) {
        return FlwApiLink.INSTANCE.lodTerrainSurface(type, minX, minZ, size);
    }
}
