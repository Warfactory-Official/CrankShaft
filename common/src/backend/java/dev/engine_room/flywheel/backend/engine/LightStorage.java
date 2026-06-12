package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

/**
 * A managed arena of light sections for uploading to the GPU.
 *
 * <p>Each section represents an 18x18x18 block volume of light data.
 * The "edges" are taken from the neighboring sections, so that each
 * shader invocation only needs to access a single section of data.
 * Even still, neighboring shader invocations may need to access other sections.
 *
 * <p>Sections are logically stored as a 9x9x9 array of longs,
 * where each long holds a 2x2x2 array of light data.
 * <br>Both the greater array and the longs are packed in x, z, y order.
 *
 * <p>Thus, each section occupies 5832 bytes.
 */
public class LightStorage {
    public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
    public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION;
    public static final int SOLID_SIZE_BYTES = Mth.positiveCeilDiv(BLOCKS_PER_SECTION, Integer.SIZE) * Integer.BYTES;
    public static final int SECTION_SIZE_BYTES = SOLID_SIZE_BYTES + LIGHT_SIZE_BYTES;
    private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
    private static final int INVALID_SECTION = -1;
    public final CpuArena arena;
    public final BitSet changed = new BitSet();
    private final LevelAccessor level;
    private final LightLut lut;
    private final Long2IntMap section2ArenaIndex;
    private final LightDataCollector collector;
    private final LongSet updatedSections = new LongOpenHashSet();
    private boolean needsLutRebuild = false;
    @Nullable
    private LongSet requestedSections;

    public LightStorage(LevelAccessor level) {
        this.level = level;
        lut = new LightLut();
        arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
        section2ArenaIndex = new Long2IntOpenHashMap();
        section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
        collector = LightDataCollector.of(level);
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
        requestedSections = sections;
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
        arena.delete();
    }

    public boolean checkNeedsLutRebuildAndClear() {
        var out = needsLutRebuild;
        needsLutRebuild = false;
        return out;
    }

    public void uploadChangedSections(StagingBuffer staging, int dstVbo) {
        for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
            staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, dstVbo, i * SECTION_SIZE_BYTES);
        }
        changed.clear();
    }

    public void upload(GlBuffer buffer) {
        if (changed.isEmpty()) {
            return;
        }

        buffer.upload(arena.indexToPointer(0), arena.capacity() * SECTION_SIZE_BYTES);
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
        return lut.flatten();
    }
}
