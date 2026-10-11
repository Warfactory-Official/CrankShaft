package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.api.lod.LodSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

public final class LodCompat {
    private static final List<IntConsumer> LISTENERS = new CopyOnWriteArrayList<>();
    private static int observed = -1;

    private LodCompat() {
    }

    public static int distance() {
        return Math.max(DistantHorizonsCompat.distance(), VoxyCompat.distance());
    }

    public static void onChange(IntConsumer listener) {
        LISTENERS.add(listener);
    }

    public static int observe() {
        int distance = distance();
        if (distance != observed) {
            observed = distance;
            for (IntConsumer listener : LISTENERS) {
                listener.accept(distance);
            }
        }
        return distance;
    }

    /**
     * Level frame terrain depth for the LoD fade: 0 after opaque terrain, 1 before translucent terrain, 2 after it.
     */
    public static void captureTerrainDepth(int stage) {
        if (DistantHorizonsCompat.ACTIVE) {
            LodDepthPass.captureTerrain(stage);
        }
    }

    public static CompletableFuture<LodSurface> surface(Heightmap.Types type, int minX, int minZ, int size) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && DistantHorizonsCompat.distance() > 0) {
            return DistantHorizonsCompat.surface(level, type, minX, minZ, size);
        }
        if (level != null && VoxyCompat.distance() > 0) {
            return VoxyCompat.surface(level, type, minX, minZ, size);
        }
        return CompletableFuture.completedFuture(new LodSurface(minX, minZ, size, null));
    }
}
