package dev.engine_room.flywheel.impl.far;

import dev.engine_room.flywheel.api.visualization.FarVisualRegistry;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bounded, nearest-first reconstruction of sparse far placements from the host's durable terrain data.
 */
final class FarTerrainCache implements AutoCloseable {
    private static final int MAX_PAGES = 4096;
    private static final int MAX_READS = 4;
    private static final int PAGES_PER_FRAME = 4;

    private final Level level;
    private final FarVisuals visuals;
    private final Long2ObjectOpenHashMap<Page> pages = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Page> waiting = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Completion> completed = new ConcurrentLinkedQueue<>();
    private final LongLinkedOpenHashSet restoring = new LongLinkedOpenHashSet();
    private @Nullable FarTerrainSource source;
    private int inFlight;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int radius = -1;
    private int registryVersion = -1;

    FarTerrainCache(Level level, FarVisuals visuals) {
        this.level = level;
        this.visuals = visuals;
    }

    void prepare(Vec3 camera) {
        if (!(level instanceof ClientLevel clientLevel)) return;
        if (source != null && !source.valid(clientLevel)) close();
        if (!FarVisualRegistry.hasProviders() || !FarVisuals.available()) {
            close();
            return;
        }
        if (source == null) {
            if (VoxyCompat.ACTIVE) source = VoxyFarSource.open(clientLevel);
            else if (DistantHorizonsCompat.ACTIVE) source = DhFarSource.open(clientLevel);
            if (source == null) return;
        }

        int version = FarVisualRegistry.version();
        if (registryVersion != version) {
            registryVersion = version;
            visuals.terrainDefinitionsChanged();
            for (var page : pages.values()) {
                if (page.snapshot != null) visuals.replaceTerrainPage(page.snapshot, null);
                page.snapshot = null;
                invalidate(page);
            }
            restoring.clear();
        }
        updateArea(camera);
        source.drainChanges(position -> {
            var page = pages.get(position);
            if (page != null) invalidate(page);
        });
        for (int i = 0; i < PAGES_PER_FRAME && !restoring.isEmpty(); i++) {
            var page = pages.get(restoring.removeFirstLong());
            if (page != null && page.snapshot != null) {
                visuals.replaceTerrainPage(page.snapshot, page.snapshot);
            }
        }
        for (int applied = 0; applied < PAGES_PER_FRAME; applied++) {
            var result = completed.poll();
            if (result == null) break;
            if (result.source != source) continue;
            inFlight--;
            var page = result.page;
            page.reading = false;
            if (pages.get(page.position) != page) continue;
            if (result.version != page.version) {
                enqueue(page);
                continue;
            }
            if (result.failure != null) throw new CompletionException(result.failure);
            if (result.snapshot != null) {
                visuals.replaceTerrainPage(page.snapshot, result.snapshot);
                page.snapshot = result.snapshot;
            }
        }
        while (inFlight < MAX_READS && !waiting.isEmpty()) {
            var page = waiting.removeFirst();
            page.queued = false;
            if (pages.get(page.position) != page || page.reading) continue;
            page.reading = true;
            inFlight++;
            var readingSource = source;
            long readingVersion = page.version;
            readingSource.read(ChunkPos.getX(page.position), ChunkPos.getZ(page.position))
                         .whenComplete((snapshot, failure) -> completed.add(
                                 new Completion(readingSource, page, readingVersion, snapshot, failure)));
        }
    }

    void restoreResources() {
        restoring.clear();
        for (var page : pages.values()) {
            if (page.snapshot != null) restoring.add(page.position);
        }
    }

    void chunkDropping(ChunkPos chunk) {
        if (source == null) return;
        int shift = source.pageShift() - 4;
        long position = ChunkPos.pack(chunk.x() >> shift, chunk.z() >> shift);
        var page = pages.get(position);
        if (page != null && page.snapshot != null) restoring.add(position);
    }

    private void updateArea(Vec3 camera) {
        int shift = source.pageShift();
        int x = Mth.floor(camera.x) >> shift;
        int z = Mth.floor(camera.z) >> shift;
        int pageRadius = Math.min((int) Math.ceil((double) LodCompat.distance() / (1 << shift)),
                (int) Math.ceil(Math.sqrt(MAX_PAGES / Math.PI)));
        if (x == centerX && z == centerZ && pageRadius == radius) return;
        centerX = x;
        centerZ = z;
        radius = pageRadius;

        var candidates = new ArrayList<Candidate>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distance = dx * dx + dz * dz;
                if (distance <= radius * radius) {
                    candidates.add(new Candidate(ChunkPos.pack(x + dx, z + dz), distance));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::distance));
        var desired = new LongOpenHashSet();
        for (int i = 0; i < Math.min(candidates.size(), MAX_PAGES); i++) {
            desired.add(candidates.get(i).position);
        }
        var iterator = pages.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!desired.contains(entry.getLongKey())) {
                if (entry.getValue().snapshot != null) visuals.replaceTerrainPage(entry.getValue().snapshot, null);
                iterator.remove();
            }
        }
        waiting.clear();
        for (int i = 0; i < Math.min(candidates.size(), MAX_PAGES); i++) {
            long position = candidates.get(i).position;
            var page = pages.get(position);
            if (page == null) {
                page = new Page(position);
                pages.put(position, page);
                enqueue(page);
            } else if (page.queued) {
                waiting.addLast(page);
            }
        }
    }

    private void invalidate(Page page) {
        page.version++;
        if (!page.reading) enqueue(page);
    }

    private void enqueue(Page page) {
        if (page.queued) return;
        page.queued = true;
        waiting.addLast(page);
    }

    @Override
    public void close() {
        if (source != null) source.close();
        source = null;
        for (var page : pages.values()) {
            if (page.snapshot != null) visuals.replaceTerrainPage(page.snapshot, null);
        }
        pages.clear();
        waiting.clear();
        completed.clear();
        inFlight = 0;
        centerX = centerZ = Integer.MIN_VALUE;
        radius = -1;
        restoring.clear();
    }

    private static final class Page {
        final long position;
        long version;
        boolean queued;
        boolean reading;
        @Nullable FarTerrainPage snapshot;

        Page(long position) {
            this.position = position;
        }
    }

    private record Candidate(long position, int distance) {
    }

    private record Completion(FarTerrainSource source, Page page, long version,
                              @Nullable FarTerrainPage snapshot, @Nullable Throwable failure) {
    }
}
