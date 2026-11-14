package dev.engine_room.flywheel.impl.config;

import net.minecraft.resources.Identifier;

/**
 * Client-thread disclosure state consumed by inline Sodium controls; changing it queues a list layout update.
 */
public interface SodiumTableView {
    boolean flywheel$isExpanded(Identifier id);

    void flywheel$toggleTable(Identifier id);

    void flywheel$restoreScroll(int scroll);
}
