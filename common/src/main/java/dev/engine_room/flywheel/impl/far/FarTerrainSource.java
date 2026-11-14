package dev.engine_room.flywheel.impl.far;

import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

/**
 * Detailed terrain source; reads own their data and never load Minecraft chunks or generate terrain.
 */
public interface FarTerrainSource extends AutoCloseable {
    int pageShift();

    /**
     * A null result means unknown source data; an empty page is known to contain no registered placements.
     */
    CompletableFuture<@Nullable FarTerrainPage> read(int pageX, int pageZ);

    /**
     * Drains accepted source changes as horizontal page coordinates packed with ChunkPos. Render thread.
     */
    void drainChanges(LongConsumer consumer);

    boolean valid(ClientLevel level);

    @Override
    void close();
}
