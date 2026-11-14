package dev.engine_room.flywheel.api.visualization;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client registrations for blocks that can be represented without a live block entity.
 * Register during client initialization or when visual configuration changes. Lookups
 * publish an immutable snapshot and may run on source workers; provider creation itself
 * remains a render-thread operation.
 */
public final class FarVisualRegistry {
    private static volatile Snapshot snapshot = new Snapshot(Map.of(), 0);

    private FarVisualRegistry() {
    }

    /**
     * Replaces this identifier's registration, including its previous block associations.
     */
    public static synchronized void register(Identifier id, FarVisualProvider provider, Block... blocks) {
        var previous = snapshot;
        var definitions = new IdentityHashMap<>(previous.definitions());
        definitions.values().removeIf(definition -> definition.id().equals(id));
        var definition = new Definition(id, provider);
        for (var block : blocks) {
            definitions.put(block, definition);
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(definitions), previous.version() + 1);
    }

    public static synchronized void unregister(Identifier id) {
        var previous = snapshot;
        var definitions = new IdentityHashMap<>(previous.definitions());
        boolean removed = definitions.values().removeIf(definition -> definition.id().equals(id));
        if (removed) {
            snapshot = new Snapshot(Collections.unmodifiableMap(definitions), previous.version() + 1);
        }
    }

    public static @Nullable Definition get(BlockState state) {
        return snapshot.definitions().get(state.getBlock());
    }

    public static boolean hasProviders() {
        return !snapshot.definitions().isEmpty();
    }

    public static int version() {
        return snapshot.version();
    }

    public record Definition(Identifier id, FarVisualProvider provider) {
    }

    private record Snapshot(Map<Block, Definition> definitions, int version) {
    }
}
