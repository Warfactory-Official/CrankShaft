package dev.engine_room.flywheel.impl.far;

#if MC >= 26.3

import it.unimi.dsi.fastutil.longs.Long2LongMap;

/**
 * Static section revisions owned by one worker-to-render-thread publication packet.
 */
public interface VoxyStaticSyncPayload {
    void flywheel$mergeStaticRevisions(Long2LongMap revisions);

    Long2LongMap flywheel$takeStaticRevisions();
}

#endif
