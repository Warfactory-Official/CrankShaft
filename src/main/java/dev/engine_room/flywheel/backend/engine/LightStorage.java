package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.lib.compat.DynamicLightProvider;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.task.ForEachPlan;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.HitboxComponent;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.joml.Matrix4f;
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
public class LightStorage implements Effect {
    public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
    public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION;
    public static final int SOLID_SIZE_BYTES = MoreMath.ceilingDiv(BLOCKS_PER_SECTION, Integer.SIZE) * Integer.BYTES;
    public static final int SECTION_SIZE_BYTES = SOLID_SIZE_BYTES + LIGHT_SIZE_BYTES;
    private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
    private static final int INVALID_SECTION = -1;

    private final World level;
    private final LightLut lut;
    public final CpuArena arena;
    private final Long2IntMap section2ArenaIndex;
    private final LightDataCollector collector;

    private final BitSet changed = new BitSet();
    private boolean needsLutRebuild = false;
    private boolean isDebugOn = false;

    private final LongSet updatedSections = new LongOpenHashSet();
    @Nullable
    private LongSet requestedSections;
    // Sections baked this frame, consumed by the dedicated dynamic-lights apply plan.
    private final LongArrayList recentlyCollectedSections = new LongArrayList();

    public LightStorage(World level) {
        this.level = level;
        lut = new LightLut();
        arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
        section2ArenaIndex = new Long2IntOpenHashMap();
        section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
        collector = LightDataCollector.of(level);
    }

    @Override
    public World level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new DebugVisual(ctx, partialTick);
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

    /**
     * Observe a flip of {@link BackendDebugFlags#LIGHT_STORAGE_VIEW} and self-add or self-remove
     * from the effects manager. Must be called on the render thread — the effects transaction
     * queue is SPSC (main-thread producer only).
     */
    public void tickDebugVisualization() {
        if (BackendDebugFlags.LIGHT_STORAGE_VIEW == isDebugOn) {
            return;
        }
        var visualizationManager = VisualizationManager.get(level);
        if (visualizationManager != null) {
            if (BackendDebugFlags.LIGHT_STORAGE_VIEW) {
                visualizationManager.effects().queueAdd(this);
            } else {
                visualizationManager.effects().queueRemove(this);
            }
        }
        isDebugOn = BackendDebugFlags.LIGHT_STORAGE_VIEW;
    }

    public <C> Plan<C> createFramePlan() {
        Plan<C> bake = SimplePlan.of(() -> {
            recentlyCollectedSections.clear();

            // CDL/OF affected sections are queued via VisualizationManagerImpl.onLightUpdate from
            // MixinRenderGlobal HEAD (main thread, before this plan is submitted). That path
            // populates both updatedSections AND LightUpdatedVisualStorage's dirty set so the
            // instance lightmap cache refreshes — the latter is what we'd miss if we only added
            // to updatedSections directly from here.
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

            // Capture the just-baked sections for the dedicated dynamic-lights apply plan.
            if (DynamicLightProvider.ANY_LOADED) {
                for (long s : sectionsToCollect) {
                    recentlyCollectedSections.add(s);
                }
            }

            updatedSections.clear();
            requestedSections = null;
        });

        if (!DynamicLightProvider.ANY_LOADED) {
            return bake;
        }

        // Per-section apply runs in parallel: each just-baked section is an independent
        // memory region in the arena, so workers don't contend. The active provider's source
        // data is quiescent for the duration of the framePlan (main thread blocked on
        // frameFlag), and OF specifically captured a stable double[] snapshot at HEAD.
        Plan<C> apply = ForEachPlan.of(() -> recentlyCollectedSections, (Long boxedSection) -> {
            long section = boxedSection;
            int idx = section2ArenaIndex.get(section);
            if (idx == INVALID_SECTION) {
                return;
            }
            long lightBase = arena.indexToPointer(idx) + SOLID_SIZE_BYTES;
            int sx = SectionPos.x(section);
            int sy = SectionPos.y(section);
            int sz = SectionPos.z(section);
            DynamicLightProvider.INSTANCE.applyToSection(lightBase, sx, sy, sz);
        });

        return bake.then(apply);
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

    public IntArrayList createLut() {
        return lut.flatten();
    }

    public class DebugVisual implements EffectVisual<LightStorage>, SimpleDynamicVisual {
        private final InstanceRecycler<TransformedInstance> boxes;
        private final Vec3i renderOrigin;
        private final Matrix4f scratch = new Matrix4f();

        public DebugVisual(VisualizationContext ctx, float partialTick) {
            renderOrigin = ctx.renderOrigin();
            boxes = new InstanceRecycler<>(() -> (TransformedInstance) ctx.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, HitboxComponent.BOX_MODEL)
                    .createInstance());
        }

        @Override
        public void beginFrame(Context ctx) {
            boxes.resetCount();

            setupSectionBoxes();
            setupLutRangeBoxes();

            boxes.discardExtra();
        }

        private void setupSectionBoxes() {
            section2ArenaIndex.keySet().forEach(l -> {
                float x = SectionPos.x(l) * 16 - renderOrigin.getX();
                float y = SectionPos.y(l) * 16 - renderOrigin.getY();
                float z = SectionPos.z(l) * 16 - renderOrigin.getZ();

                // 14x14x14 (vs the full 16x16x16) so tiled sections stay individually visible.
                scratch.identity().translate(x + 1, y + 1, z + 1).scale(14);
                boxes.get()
                        .setTransform(scratch)
                        .color(255, 255, 0)
                        .light(LightTexture.FULL_BRIGHT)
                        .setChanged();
            });
        }

        private void setupLutRangeBoxes() {
            var first = lut.indices;
            int base1 = first.base();
            int size1 = first.size();
            float debug1 = base1 * 16 - renderOrigin.getY();

            float min2 = Float.POSITIVE_INFINITY;
            float max2 = Float.NEGATIVE_INFINITY;
            float min3 = Float.POSITIVE_INFINITY;
            float max3 = Float.NEGATIVE_INFINITY;

            for (int y = 0; y < size1; y++) {
                var second = first.getRaw(y);
                if (second == null) continue;

                int base2 = second.base();
                int size2 = second.size();
                float y2 = (base1 + y) * 16 - renderOrigin.getY() + 7.5f;

                min2 = Math.min(min2, base2);
                max2 = Math.max(max2, base2 + size2);

                float minLocal3 = Float.POSITIVE_INFINITY;
                float maxLocal3 = Float.NEGATIVE_INFINITY;
                float debug2 = base2 * 16 - renderOrigin.getX();

                for (int x = 0; x < size2; x++) {
                    var third = second.getRaw(x);
                    if (third == null) continue;

                    int base3 = third.base();
                    int size3 = third.size();
                    float x2 = (base2 + x) * 16 - renderOrigin.getX() + 7.5f;

                    min3 = Math.min(min3, base3);
                    max3 = Math.max(max3, base3 + size3);
                    minLocal3 = Math.min(minLocal3, base3);
                    maxLocal3 = Math.max(maxLocal3, base3 + size3);

                    float debug3 = base3 * 16 - renderOrigin.getZ();

                    for (int z = 0; z < size3; z++) {
                        scratch.identity().translate(x2, y2, debug3).scale(1, 1, size3 * 16);
                        boxes.get()
                                .setTransform(scratch)
                                .color(0, 0, 255)
                                .light(LightTexture.FULL_BRIGHT)
                                .setChanged();
                    }
                }

                scratch.identity()
                        .translate(debug2, y2, minLocal3 * 16 - renderOrigin.getZ())
                        .scale(size2 * 16, 1, (maxLocal3 - minLocal3) * 16);
                boxes.get()
                        .setTransform(scratch)
                        .color(255, 0, 0)
                        .light(LightTexture.FULL_BRIGHT)
                        .setChanged();
            }

            scratch.identity()
                    .translate(min2 * 16 - renderOrigin.getX(), debug1, min3 * 16 - renderOrigin.getZ())
                    .scale((max2 - min2) * 16, size1 * 16, (max3 - min3) * 16);
            boxes.get()
                    .setTransform(scratch)
                    .color(0, 255, 0)
                    .light(LightTexture.FULL_BRIGHT)
                    .setChanged();
        }

        @Override
        public void update(float partialTick) {
        }

        @Override
        public void delete() {
            boxes.delete();
        }
    }
}
