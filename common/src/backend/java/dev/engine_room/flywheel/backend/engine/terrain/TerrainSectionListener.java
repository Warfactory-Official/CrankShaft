package dev.engine_room.flywheel.backend.engine.terrain;

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

    void onSectionMeshed(int regionId, int originX, int originY, int originZ, int localIndex,
                         long dataPtrSolid, long dataPtrCutout, long dataPtrTranslucent, int geometryHandle);

    void onSectionRemoved(int regionId, int localIndex);

    void onRegionFreed(int regionId);

    void noteRegionIdentity(int regionId, int originX, int originY, int originZ, int geometryHandle);

    int cachedGeometryHandle(int regionId);

    final class Holder {
        @Nullable
        private static volatile TerrainSectionListener published;
        // Lifetime-scoped (attach on construct / detach on delete) so Hook 3 can invalidate a freed region even
        // while the takeover is unpublished.
        @Nullable
        private static volatile TerrainSectionListener attached;

        private Holder() {
        }
    }
}
