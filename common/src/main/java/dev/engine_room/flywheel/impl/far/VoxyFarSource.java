package dev.engine_room.flywheel.impl.far;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

import org.jetbrains.annotations.Nullable;
import dev.engine_room.flywheel.api.visualization.FarVisualRegistry;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads registered far visuals from Voxy's finest persisted sections. Reads own
 * world references until completion; Voxy shutdown detaches sources before it
 * waits for those references and closes its storage.
 */
public final class VoxyFarSource implements FarTerrainSource {
    private static final ExecutorService READERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("CrankShaft Voxy far read ", 0).factory());
    private static final ThreadLocal<long[]> SECTION_DATA = ThreadLocal.withInitial(
            () -> new long[WorldSection.SECTION_VOLUME]);
    private static final Set<VoxyFarSource> SOURCES = ConcurrentHashMap.newKeySet();

    private final ClientLevel level;
    private final WorldIdentifier identifier;
    private final WorldEngine engine;
    private final int minY;
    private final int maxY;
    private final Set<Long> changedPages = ConcurrentHashMap.newKeySet();
    private final Object paletteLock = new Object();
    private volatile @Nullable Palette palette;
    private volatile boolean closed;

    private VoxyFarSource(ClientLevel level, WorldIdentifier identifier, WorldEngine engine) {
        this.level = level;
        this.identifier = identifier;
        this.engine = engine;
        minY = level.getMinY();
        maxY = level.getMaxY();
    }

    /**
     * Called on the client thread after Voxy has selected its world.
     */
    public static synchronized @Nullable VoxyFarSource open(ClientLevel level) {
        var identifier = WorldIdentifier.of(level);
        if (identifier == null) {
            return null;
        }
        var engine = identifier.getNullable();
        if (engine == null || engine.instanceIn.flywheel$farSourcesClosing()) {
            return null;
        }

        var access = engine;
        var existing = access.flywheel$getFarSource();
        if (existing != null) {
            if (existing.valid(level)) {
                return existing;
            }
            existing.close();
        }

        engine.acquireRef();
        var source = new VoxyFarSource(level, identifier, engine);
        SOURCES.add(source);
        access.flywheel$setFarSource(source);
        return source;
    }

    /**
     * Called before Voxy shutdown waits for world references.
     */
    public static synchronized void closeInstance(VoxyInstance instance) {
        for (var source : SOURCES) {
            if (source.engine.instanceIn == instance) {
                source.close();
            }
        }
    }

    @Override
    public int pageShift() {
        return 5;
    }

    @Override
    public synchronized CompletableFuture<FarTerrainPage> read(int pageX, int pageZ) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        engine.acquireRef();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readPage(pageX, pageZ);
            } finally {
                engine.releaseRef();
            }
        }, READERS);
    }

    private @Nullable FarTerrainPage readPage(int pageX, int pageZ) {
        var positions = new LongArrayList();
        var states = new ObjectArrayList<BlockState>();
        var lights = new IntArrayList();
        var data = SECTION_DATA.get();
        long registryVersion = FarVisualRegistry.version();
        var statePalette = paletteFor(-1, registryVersion);
        boolean known = false;
        int originX = pageX << 5;
        int originZ = pageZ << 5;

        for (int sectionY = minY >> 5; sectionY <= maxY >> 5; sectionY++) {
            if (closed || !engine.instanceIn.isRunning()) {
                return null;
            }
            var section = engine.acquireIfExists(0, pageX, sectionY, pageZ);
            if (section == null) {
                continue;
            }
            known = true;
            try {
                section.copyDataTo(data);
            } finally {
                section.release();
            }

            int originY = sectionY << 5;
            int start = (Math.max(minY, originY) - originY) << 10;
            int end = (Math.min(maxY + 1, originY + 32) - originY) << 10;
            for (int index = start; index < end; index++) {
                long voxel = data[index];
                int blockId = Mapper.getBlockId(voxel);
                if (blockId == 0) {
                    continue;
                }
                if (blockId >= statePalette.states.length) {
                    statePalette = paletteFor(blockId, registryVersion);
                }
                var state = statePalette.states[blockId];
                if (state == null) {
                    continue;
                }
                int x = originX + (index & 31);
                int y = originY + (index >> 10);
                int z = originZ + ((index >> 5) & 31);
                int light = Mapper.getLightId(voxel);
                int packedLight = ((light >>> 4) << 4) | ((light & 15) << 20);
                positions.add(BlockPos.asLong(x, y, z));
                states.add(state);
                lights.add(packedLight);
            }
        }
        if (!known) {
            return null;
        }
        return new FarTerrainPage(pageX, pageZ, positions.toLongArray(), states.toArray(BlockState[]::new),
                lights.toIntArray());
    }

    private Palette paletteFor(int blockId, long registryVersion) {
        var current = palette;
        if (current != null && current.registryVersion == registryVersion && blockId < current.states.length) {
            return current;
        }
        synchronized (paletteLock) {
            current = palette;
            if (current != null && current.registryVersion == registryVersion && blockId < current.states.length) {
                return current;
            }
            var entries = engine.getMapper().getStateEntries();
            var states = new BlockState[entries.length];
            for (var entry : entries) {
                if (FarVisualRegistry.get(entry.state) != null) {
                    states[entry.id] = entry.state;
                }
            }
            current = new Palette(registryVersion, states);
            palette = current;
            return current;
        }
    }

    /**
     * Receives accepted finest-section changes, including imported server data.
     */
    public void changed(int pageX, int pageZ) {
        if (!closed) {
            changedPages.add(ChunkPos.pack(pageX, pageZ));
        }
    }

    @Override
    public void drainChanges(LongConsumer consumer) {
        var iterator = changedPages.iterator();
        while (iterator.hasNext()) {
            long page = iterator.next();
            iterator.remove();
            consumer.accept(page);
        }
    }

    @Override
    public boolean valid(ClientLevel level) {
        return !closed && this.level == level && identifier.getNullable() == engine;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.flywheel$setFarSource(null);
        SOURCES.remove(this);
        changedPages.clear();
        engine.releaseRef();
    }

    private record Palette(long registryVersion, BlockState[] states) {
    }

    /**
     * The world owns the callback attachment; Voxy's dirty callback is independent.
     */
    public interface Access {
        @Nullable VoxyFarSource flywheel$getFarSource();

        void flywheel$setFarSource(@Nullable VoxyFarSource source);
    }

    /**
     * Prevents a new attachment after shutdown starts and before Voxy stops running.
     */
    public interface InstanceAccess {
        boolean flywheel$farSourcesClosing();
    }
}
