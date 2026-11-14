package dev.engine_room.flywheel.impl.compat;

import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Opaque sections selected by DH's existing quadtree and frustum walk for the current view.
 */
public interface DhStaticSelection {
    LongList flywheel$staticSections();
}
