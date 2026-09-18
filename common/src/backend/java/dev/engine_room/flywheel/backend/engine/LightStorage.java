package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.lighting.GeometryOcclusion;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.lighting.*;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

/**
 * Shared GPU light data: 18-cubed section neighborhoods with light bytes and solid bits.
 * The LUT also carries opt-in occlusion geometry. Frame preparation follows visual updates;
 * backend uploads consume the prepared arrays after the frame plan joins.
 */
public class LightStorage {
    public static final int BLOCKS_PER_SECTION = LightPacking.BLOCKS_PER_SECTION;
    public static final int LIGHT_SIZE_BYTES = LightPacking.LIGHT_SIZE_BYTES;
    public static final int SOLID_SIZE_BYTES = LightPacking.SOLID_SIZE_BYTES;
    public static final int SECTION_SIZE_BYTES = LightPacking.SECTION_SIZE_BYTES;
    private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
    private static final int INVALID_SECTION = -1;
    public final CpuArena arena;
    public final BitSet changed = new BitSet();
    private final LevelAccessor level;
    private final LightLut lut;
    private final Long2IntMap section2ArenaIndex;
    private final LightDataCollector collector;
    private final GeometryAoStorage geometry;
    private final @Nullable WorldLighting worldLighting;
    private final WorldLighting.@Nullable Subscription geometryInterest;
    private long geometryLayoutRevision = -1, geometryPoseRevision = -1;
    private Vec3i renderOrigin = BlockPos.ZERO;
    private final LongSet updatedSections = new LongOpenHashSet();
    private boolean needsLutRebuild = true;
    private boolean geometryPosesChanged;
    private int geometryPoseOffset;
    private int lutWords;
    private final IntArrayList geometryRanges = new IntArrayList();
    private final LutUpdates lutUpdates = new LutUpdates(geometryRanges);
    @Nullable
    private LongSet requestedSections;

    public LightStorage(LevelAccessor level) {
        this.level = level;
        lut = new LightLut();
        arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
        section2ArenaIndex = new Long2IntOpenHashMap();
        section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
        collector = LightDataCollector.of(level);
        worldLighting = level instanceof ClientLevel client ? WorldLighting.of(client) : null;
        geometryInterest = worldLighting == null ? null : worldLighting.subscribe();
        geometry = worldLighting == null ? new GeometryAoStorage() : worldLighting.geometry();
    }

    public LevelAccessor level() {
        return level;
    }

    /**
     * Set the set of requested sections.
     * <p> When set, this will be processed in the next frame plan. It may not be set every frame.
     *
     * @param sections The set of sections requested by the impl.
     */
    public void sections(LongSet sections) {
        requestedSections = new LongOpenHashSet(sections);
    }

    public GeometryOcclusion geometryOcclusion() {
        return geometry;
    }

    public void renderOrigin(Vec3i origin) {
        renderOrigin = origin;
    }

    public void geometrySections(LongSet sections) {
        if (geometryInterest != null) geometryInterest.sections(sections);
        else if (!sections.isEmpty())
            throw new UnsupportedOperationException("Terrain lighting requires a client world");
    }

    public void flushTerrainRequests() {
        if (worldLighting != null) worldLighting.prepare();
        else geometry.prepare(renderOrigin);
    }

    public void onLightUpdate(long section) {
        updatedSections.add(section);
    }

    public <C> Plan<C> createFramePlan() {
        return SimplePlan.of(() -> {
            if (updatedSections.isEmpty() && requestedSections == null) {
                return;
            }

            removeUnusedSections();

            // Start building the set of sections we need to collect this frame.
            LongSet sectionsToCollect;
            if (requestedSections == null) {
                // If none were requested, then we need to collect all sections that received updates.
                sectionsToCollect = new LongOpenHashSet();
            } else {
                // If we did receive a new set of requested sections, we only
                // need to collect the sections that weren't yet tracked.
                sectionsToCollect = new LongOpenHashSet(requestedSections);
                sectionsToCollect.removeAll(section2ArenaIndex.keySet());
            }

            // updatedSections contains all sections that received light updates,
            // but we only care about its intersection with our tracked sections.
            for (long updatedSection : updatedSections) {
                // Since sections contain the border light of their neighbors, we need to collect the neighbors as well.
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            long section = SectionPos.offset(updatedSection, x, y, z);
                            if (section2ArenaIndex.containsKey(section)) {
                                sectionsToCollect.add(section);
                            }
                        }
                    }
                }
            }

            // Now actually do the collection.
            sectionsToCollect.forEach(this::collectSection);

            updatedSections.clear();
            requestedSections = null;
        });
    }

    private void removeUnusedSections() {
        if (requestedSections == null) {
            return;
        }

        boolean anyRemoved = false;

        var entries = section2ArenaIndex.long2IntEntrySet();
        var it = entries.iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var section = entry.getLongKey();

            if (!requestedSections.contains(section)) {
                arena.free(entry.getIntValue());
                endTrackingSection(section);
                it.remove();
                anyRemoved = true;
            }
        }

        if (anyRemoved) {
            lut.prune();
            needsLutRebuild = true;
        }
    }

    private void beginTrackingSection(long section, int index) {
        lut.add(section, index);
        needsLutRebuild = true;
    }

    private void endTrackingSection(long section) {
        lut.remove(section);
        needsLutRebuild = true;
    }

    public int capacity() {
        return arena.capacity();
    }

    public void collectSection(long section) {
        int index = indexForSection(section);

        changed.set(index);

        long ptr = arena.indexToPointer(index);

        // Zero it out first. This is basically free and makes it easier to handle missing sections later.
        MemoryUtil.memSet(ptr, 0, SECTION_SIZE_BYTES);

        collector.collectSection(ptr, section);
    }

    private int indexForSection(long section) {
        int out = section2ArenaIndex.get(section);

        // Need to allocate.
        if (out == INVALID_SECTION) {
            out = arena.alloc();
            section2ArenaIndex.put(section, out);
            beginTrackingSection(section, out);
        }
        return out;
    }

    public void delete() {
        if (geometryInterest != null) geometryInterest.close();
        else geometry.delete();
        arena.delete();
    }

    public @Nullable LutUpdates pollLutUpdates() {
        flushTerrainRequests();
        long previousPoseRevision = geometryPoseRevision;
        if (geometryLayoutRevision != geometry.layoutRevision()) needsLutRebuild = true;
        else if (geometryPoseRevision != geometry.poseRevision()) geometryPosesChanged = true;
        geometryLayoutRevision = geometry.layoutRevision();
        geometryPoseRevision = geometry.poseRevision();
        if (needsLutRebuild) {
            IntArrayList words = createLut();
            lutWords = words.size();
            geometryPoseOffset = geometry.isEmpty() ? 0 : words.getInt(0) + geometry.staticWords();
            needsLutRebuild = false;
            geometryPosesChanged = false;
            geometryRanges.clear();
            geometryRanges.add(0);
            geometryRanges.add(lutWords);
            lutUpdates.set(0, lutWords, words.elements(), words);
            return lutUpdates;
        }
        if (geometryPosesChanged) {
            geometryPosesChanged = false;
            geometry.fillDynamicRanges(previousPoseRevision, geometryRanges);
            if (geometryRanges.isEmpty()) return null;
            lutUpdates.set(geometryPoseOffset, lutWords, geometry.preparedDynamicWords(), null);
            return lutUpdates;
        }
        return null;
    }

    public void uploadChangedSections(StagingBuffer staging, int dstVbo) {
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, dstVbo, (long) i * SECTION_SIZE_BYTES);
        }
        changed.clear();
    }

    public void upload(GlBuffer buffer) {
        if (changed.isEmpty()) {
            return;
        }

        buffer.upload(arena.indexToPointer(0), (long) arena.capacity() * SECTION_SIZE_BYTES);
        changed.clear();
    }

    // For the instancing backend's Mojang GpuBuffer-backed light (InstancedLight uploads the whole arena via
    // the RHI encoder rather than the raw GlBuffer/StagingBuffer paths above): the section data span + the
    // changed-section gating those paths own internally.
    public boolean hasSectionChanges() {
        return !changed.isEmpty();
    }

    public void clearSectionChanges() {
        changed.clear();
    }

    public long sectionDataPointer() {
        return arena.indexToPointer(0);
    }

    public int sectionDataBytes() {
        return arena.capacity() * SECTION_SIZE_BYTES;
    }

    public IntArrayList createLut() {
        var words = new IntArrayList();
        words.add(0);
        lut.indices.fillLut(words, (y, out) -> y.fillLut(out, LightLut.IntLayer::fillLut));
        if (!geometry.isEmpty()) {
            words.set(0, words.size());
            geometry.append(words);
        }
        return words;
    }

    /**
     * Reused until the next poll; callers consume all spans before returning to the draw manager.
     */
    public static final class LutUpdates {
        private final IntArrayList ranges;
        private int baseOffset, totalWords;
        private int[] words;
        private @Nullable IntArrayList fullWords;

        private LutUpdates(IntArrayList ranges) {
            this.ranges = ranges;
        }

        private void set(int baseOffset, int totalWords, int[] words, @Nullable IntArrayList fullWords) {
            this.baseOffset = baseOffset;
            this.totalWords = totalWords;
            this.words = words;
            this.fullWords = fullWords;
        }

        public int count() {
            return ranges.size() / 2;
        }

        public int offset(int index) {
            return baseOffset + ranges.getInt(index * 2);
        }

        public int source(int index) {
            return ranges.getInt(index * 2);
        }

        public int length(int index) {
            return ranges.getInt(index * 2 + 1) - source(index);
        }

        public int[] words() {
            return words;
        }

        public int word(int index) {
            return words[index];
        }

        public int totalWords() {
            return totalWords;
        }

        public @Nullable IntArrayList fullWords() {
            return fullWords;
        }
    }
}
