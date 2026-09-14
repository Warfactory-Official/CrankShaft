package dev.engine_room.flywheel.backend.engine.terrain;

import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jspecify.annotations.Nullable;

public interface TerrainSectionListener {
    @Nullable
    static TerrainSectionListener published() {
        return Holder.published;
    }

    static void publish(TerrainSectionListener listener) {
        Holder.published = listener;
    }

    static void unpublish(TerrainSectionListener listener) {
        if (Holder.published == listener) {
            Holder.published = null;
        }
    }

    @Nullable
    static TerrainSectionListener attached() {
        return Holder.attached;
    }

    static void attach(TerrainSectionListener listener) {
        Holder.attached = listener;
    }

    static void detach(TerrainSectionListener listener) {
        if (Holder.attached == listener) {
            Holder.attached = null;
        }
    }

    void markSection(RenderRegion region, int localIndex);

    void markRegion(RenderRegion region);

    void onSectionRemoved(int regionId, int localIndex);

    void onRegionFreed(int regionId);

    final class Holder {
        @Nullable
        private static volatile TerrainSectionListener published;
        // Region frees must reach the registry while the takeover is unpublished.
        @Nullable
        private static volatile TerrainSectionListener attached;

        private Holder() {
        }
    }
}
