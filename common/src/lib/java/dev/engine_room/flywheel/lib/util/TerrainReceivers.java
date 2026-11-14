package dev.engine_room.flywheel.lib.util;

import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captured face identities. Registrations change on the render thread; meshing workers borrow immutable snapshots.
 */
public final class TerrainReceivers {
    private static final Map<ClientLevel, State> LEVELS = new ConcurrentHashMap<>();
    private static final Section EMPTY_SECTION = new Section(new Int2ObjectOpenHashMap<>());
    private static final Snapshot EMPTY = new Snapshot(0, new Long2ObjectOpenHashMap<>());
    private static final Reference REJECTED = new Reference(null, 0L, false);
    private static volatile boolean active;

    private TerrainReceivers() {
    }

    public static boolean active() {
        return active;
    }

    /**
     * {@code id(i)} answers {@code surfaces.get(i)}: 0 when that owner is unloaded or no longer holds its state.
     */
    public static Registration register(ClientLevel level, List<Surface> surfaces) {
        RenderSystem.assertOnRenderThread();
        var state = LEVELS.computeIfAbsent(level, _ -> new State());
        var references = new Reference[surfaces.size()];
        var dirty = new HashSet<Long>();
        for (int index = 0; index < references.length; index++) {
            var surface = surfaces.get(index);
            if (!level.hasChunkAt(surface.owner) || level.getBlockState(surface.owner) != surface.state) {
                references[index] = REJECTED;
                continue;
            }
            var entry = state.entries.get(surface.key);
            if (entry == null) {
                if (state.nextId == 0) throw new IllegalStateException("Terrain receiver identity space exhausted");
                entry = new Entry(state.nextId++, surface.key);
                state.entries.put(surface.key, entry);
                dirty.add(surface.key.section);
            }
            entry.references++;
            var reference = new Reference(entry, surface.owner.asLong(), true);
            references[index] = reference;
            state.owners.computeIfAbsent(reference.owner, _ -> new ArrayList<>()).add(reference);
        }
        state.publish();
        if (Minecraft.getInstance().level == level) for (long section : dirty) {
            Minecraft.getInstance().levelExtractor.setSectionDirty(SectionPos.x(section), SectionPos.y(section),
                    SectionPos.z(section));
        }
        return new Registration(state, references);
    }

    public static Snapshot snapshot(ClientLevel level) {
        var state = LEVELS.get(level);
        return state == null ? EMPTY : state.snapshot;
    }

    public static void invalidate(ClientLevel level, BlockPos owner, BlockState previous, BlockState current) {
        if (previous == current) return;
        var state = LEVELS.get(level);
        if (state == null) return;
        var references = state.owners.remove(owner.asLong());
        if (references == null) return;
        for (var reference : references) state.release(reference);
        state.publish();
    }

    public static void invalidateChunk(ClientLevel level, int chunkX, int chunkZ) {
        var state = LEVELS.get(level);
        if (state == null) return;
        boolean changed = false;
        for (var iterator = state.owners.long2ObjectEntrySet().fastIterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            long owner = entry.getLongKey();
            if (SectionPos.blockToSectionCoord(BlockPos.getX(owner)) != chunkX
                    || SectionPos.blockToSectionCoord(BlockPos.getZ(owner)) != chunkZ) continue;
            for (var reference : entry.getValue()) state.release(reference);
            iterator.remove();
            changed = true;
        }
        if (changed) state.publish();
    }

    public static void clear(ClientLevel level) {
        var state = LEVELS.remove(level);
        if (state == null) return;
        for (var references : state.owners.values()) for (var reference : references) reference.valid = false;
        state.owners.clear();
        state.entries.clear();
        state.publish();
    }

    private static void canonical(float[] values, int[] result) {
        int first = 0;
        for (int vertex = 1; vertex < 4; vertex++) if (compare(values, vertex, first) < 0) first = vertex;
        for (int vertex = 0; vertex < 4; vertex++)
            for (int component = 0; component < 3; component++)
                result[vertex * 3 + component] = bits(values[((first + vertex) % 4) * 5 + component]);
    }

    private static int compare(float[] values, int first, int second) {
        for (int component = 0; component < 12; component++) {
            int result = Integer.compareUnsigned(bits(values[((first + component / 3) % 4) * 5 + component % 3]),
                    bits(values[((second + component / 3) % 4) * 5 + component % 3]));
            if (result != 0) return result;
        }
        return 0;
    }

    private static int bits(float value) {
        return Float.floatToRawIntBits(value == 0 ? 0 : value);
    }

    /**
     * Four emitted vertices, each xyz/uv, in the supplied terrain section's coordinates.
     */
    public static final class Surface {
        private final BlockPos owner;
        private final BlockState state;
        private final Key key;

        public Surface(BlockPos owner, BlockState state, SectionPos section, float[] positionsUv) {
            if (positionsUv.length != 20)
                throw new IllegalArgumentException("A terrain receiver requires four xyz/uv vertices");
            this.owner = owner.immutable();
            this.state = state;
            var vertices = new int[12];
            canonical(positionsUv, vertices);
            key = new Key(section.asLong(), vertices);
        }
    }

    public static final class Registration implements AutoCloseable {
        private final State state;
        private final Reference[] references;
        private boolean closed;

        private Registration(State state, Reference[] references) {
            this.state = state;
            this.references = references;
        }

        public int size() {
            return references.length;
        }

        public int id(int index) {
            var reference = references[index];
            return reference.valid ? reference.entry.id : 0;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RenderSystem.assertOnRenderThread();
            for (var reference : references) {
                if (!reference.valid) continue;
                var owners = state.owners.get(reference.owner);
                owners.remove(reference);
                if (owners.isEmpty()) state.owners.remove(reference.owner);
                state.release(reference);
            }
            state.publish();
        }
    }

    public static final class Snapshot {
        private final long revision;
        private final Long2ObjectOpenHashMap<Section> sections;

        private Snapshot(long revision, Long2ObjectOpenHashMap<Section> sections) {
            this.revision = revision;
            this.sections = sections;
        }

        public long revision() {
            return revision;
        }

        public Section section(long section) {
            return sections.getOrDefault(section, EMPTY_SECTION);
        }
    }

    public static final class Section {
        private final Int2ObjectOpenHashMap<Entry[]> faces;

        private Section(Int2ObjectOpenHashMap<Entry[]> faces) {
            this.faces = faces;
        }

        public boolean isEmpty() {
            return faces.isEmpty();
        }

        /**
         * Scratch is twelve ints; texture coordinates do not determine a surface's identity.
         */
        public int find(float[] positionsUv, int[] scratch) {
            canonical(positionsUv, scratch);
            int hash = 1;
            for (int index = 0; index < 12; index++) hash = 31 * hash + scratch[index];
            var candidates = faces.get(hash);
            if (candidates == null) return 0;
            for (var entry : candidates) if (Arrays.equals(entry.key.vertices, 0, 12, scratch, 0, 12)) return entry.id;
            return 0;
        }
    }

    private static final class Key {
        private final long section;
        private final int[] vertices;
        private final int hash;

        private Key(long section, int[] vertices) {
            this.section = section;
            this.vertices = vertices;
            hash = 31 * Long.hashCode(section) + Arrays.hashCode(vertices);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && section == key.section && Arrays.equals(vertices, key.vertices);
        }
    }

    private static final class Entry {
        private final int id;
        private final Key key;
        private int references;

        private Entry(int id, Key key) {
            this.id = id;
            this.key = key;
        }
    }

    private static final class Reference {
        private final Entry entry;
        private final long owner;
        private volatile boolean valid;

        private Reference(Entry entry, long owner, boolean valid) {
            this.entry = entry;
            this.owner = owner;
            this.valid = valid;
        }
    }

    private static final class State {
        private final Map<Key, Entry> entries = new HashMap<>();
        private final Long2ObjectOpenHashMap<ArrayList<Reference>> owners = new Long2ObjectOpenHashMap<>();
        private volatile Snapshot snapshot = EMPTY;
        private int nextId = 1;
        private long revision;

        private void release(Reference reference) {
            reference.valid = false;
            var entry = reference.entry;
            if (--entry.references == 0) entries.remove(entry.key, entry);
        }

        private void publish() {
            var grouped = new Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<ArrayList<Entry>>>();
            for (var entry : entries.values())
                grouped.computeIfAbsent(entry.key.section, _ -> new Int2ObjectOpenHashMap<>())
                       .computeIfAbsent(Arrays.hashCode(entry.key.vertices), _ -> new ArrayList<>()).add(entry);
            var sections = new Long2ObjectOpenHashMap<Section>();
            for (var section : grouped.long2ObjectEntrySet()) {
                var faces = new Int2ObjectOpenHashMap<Entry[]>();
                section.getValue().forEach((hash, values) -> faces.put(hash, values.toArray(Entry[]::new)));
                sections.put(section.getLongKey(), new Section(faces));
            }
            snapshot = new Snapshot(++revision, sections);
            active = LEVELS.values().stream().anyMatch(state -> !state.entries.isEmpty());
        }
    }
}
