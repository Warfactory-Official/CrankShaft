package dev.engine_room.flywheel.impl.far;

#if MC >= 26.3

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarBuffer;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.engine.far.SceneOcclusion;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

/**
 * Frozen mesh membership in Voxy's section hierarchy. CPU state contains section/instance identities and ranges;
 * vertices, instance attributes and generated draw commands remain in GPU buffers. Membership is updated only
 * when captures change. The native traversal and section visibility decide which groups submit each frame.
 */
public final class VoxyStaticSections implements AutoCloseable {
    private static final int TOMBSTONE = -1;
    private final AsyncNodeManager nodes;
    private final WorldEngine world;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final StampedLock hierarchyLock = new StampedLock();
    private final Int2ObjectOpenHashMap<NativeGeometry> workerGeometry = new Int2ObjectOpenHashMap<>();
    private Long2LongOpenHashMap workerRevisions = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap workerNodeRevisions = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap nativeGeometryReady = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap nativeGeometryRequired = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap nativeGraphReady = new Long2LongOpenHashMap();
    private long nativeRegistrationRevision;
    private final LinkedHashSet<Section> pendingPages = new LinkedHashSet<>();
    private final LinkedHashSet<Section> dirtyPendingPages = new LinkedHashSet<>();
    private @Nullable BuiltSection preparedSection;
    private long preparedRevision;
    private boolean pendingCommandsDirty;
    private final LinkedHashSet<Section> dirtySections = new LinkedHashSet<>();
    private final LongOpenHashSet hierarchyDirty = new LongOpenHashSet();
    private final LongOpenHashSet roots = new LongOpenHashSet();
    private final IntArrayList freeClipIds = new IntArrayList();
    private final Int2ObjectOpenHashMap<Partition> partitions = new Int2ObjectOpenHashMap<>();
    private final AllocationArena mappingArena = arena();
    private final AllocationArena groupArena = arena();
    private final AllocationArena commandArena = arena();
    private final AllocationArena coverageArena = arena();
    private final FarBuffer hashBuffer = new FarBuffer();
    private final FarBuffer groupBuffer = new FarBuffer();
    private final FarBuffer mappingBuffer = new FarBuffer();
    private final FarBuffer clipBuffer = new FarBuffer();
    private final FarBuffer partitionBuffer = new FarBuffer();
    private final FarBuffer countBuffer = new FarBuffer();
    private final FarBuffer commandBuffer = new FarBuffer();
    private final FarBuffer mainCommands = new FarBuffer();
    private final FarBuffer mainCounts = new FarBuffer();
    private final FarBuffer coverageHeaders = new FarBuffer();
    private final FarBuffer coverageLinks = new FarBuffer();
    private final FarBuffer coverageFrames = new FarBuffer();
    private final FarBuffer pendingJobs = new FarBuffer();
    private final FarBuffer pendingCommands = new FarBuffer();
    private final LinkedHashSet<Section> dirtyCoverage = new LinkedHashSet<>();
    private final TreeMap<Integer, Integer> basePageYs = new TreeMap<>();
    private final FarStaticMeshes.Listener listener = this::changed;
    private final List<FarStaticDraw.DrawBatch> batches = new ArrayList<>();
    private final List<FarStaticDraw.DrawBatch> mainBatches = new ArrayList<>();
    private final List<FarStaticDraw.DrawBatch> pendingBatches = new ArrayList<>();
    private final List<FarStaticDraw.DrawBatch> drawPackets = new ArrayList<>();
    private @Nullable FarStaticMeshes meshes;
    private @Nullable AbstractRenderPipeline pipeline;
    private @Nullable ShaderSources programSources;
    private @Nullable MDICViewport currentViewport;
    private long currentViewEpoch = Long.MIN_VALUE;
    private long mainDrawEpoch = Long.MIN_VALUE;
    private long membershipVersion;
    private long mainMembershipVersion = -1;
    private int mainChunkX;
    private int mainChunkZ;
    private int mainRadiusBits;
    private @Nullable AsyncGlProgram commandBuild;
    private @Nullable AsyncGlProgram pendingBuild;
    private int commandProgram;
    private int pendingProgram;
    private boolean commandsReady;
    private int pendingJobCount;
    private boolean packetsDirty;
    private int nextClipId;
    private int basePages;
    private int templateCapacity;
    private int hashEntries;
    private int hashUsed;
    private int[] hashWords = new int[64];
    private boolean hashRebuild = true;

    public VoxyStaticSections(AsyncNodeManager nodes, WorldEngine world) {
        this.nodes = nodes;
        this.world = world;
    }

    private static AllocationArena arena() {
        var arena = new AllocationArena();
        arena.setLimit(Integer.MAX_VALUE);
        return arena;
    }

    public void activate(AbstractRenderPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public boolean available() {
        if (pipeline == null) return false;
        var provider = VoxyStaticVisuals.provider(pipeline);
        return provider != null && provider.available(pipeline);
    }

    /**
     * Synchronizes captures before Voxy's traversal begins. Render thread.
     */
    public void sync() {
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null) manager.prepareFarFrame();
        var far = manager == null ? null : manager.farVisuals();
        var next = far == null ? null : far.staticMeshes();
        if (meshes != next) {
            if (meshes != null) meshes.removeListener(listener);
            clearMembership();
            meshes = next;
            if (next != null) {
                next.addListener(listener);
                for (var entry : next.entries()) changed(null, entry);
            }
        }
        if (hierarchyDirty.isEmpty()) return;
        for (var iter = hierarchyDirty.longIterator(); iter.hasNext(); ) {
            long position = iter.nextLong();
            if (WorldEngine.getLevel(position) != WorldEngine.MAX_LOD_LAYER) continue;
            boolean present = sections.containsKey(position);
            boolean wasPresent = roots.contains(position);
            if (present == wasPresent) continue;
            if (present) roots.add(position);
            else roots.remove(position);
            nodes.flywheel$staticRoot(position, present);
        }
        for (var iter = hierarchyDirty.longIterator(); iter.hasNext(); ) {
            var section = world.acquire(iter.nextLong());
            try {
                nodes.worldEvent(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT
                        | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT, 0);
            } finally {
                section.release();
            }
        }
        hierarchyDirty.clear();
    }

    private void changed(FarStaticMeshes.@Nullable Entry before, FarStaticMeshes.@Nullable Entry after) {
        long stamp = hierarchyLock.writeLock();
        try {
            changedUnlocked(before, after);
        } finally {
            hierarchyLock.unlockWrite(stamp);
        }
    }

    private void changedUnlocked(FarStaticMeshes.@Nullable Entry before, FarStaticMeshes.@Nullable Entry after) {
        membershipVersion++;
        if (before == null && after == null) {
            clearMembershipUnlocked();
            return;
        }
        if (before != null) for (var instance : before.instances()) membership(instance, false);
        if (after != null) for (var instance : after.instances()) membership(instance, true);
    }

    private void membership(FarStaticMeshes.Instance instance, boolean add) {
        var bounds = instance.bounds();
        for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            int shift = level + 5;
            int minX = Mth.floor(bounds.minX()) >> shift, maxX = Mth.floor(bounds.maxX()) >> shift;
            int minY = Mth.floor(bounds.minY()) >> shift, maxY = Mth.floor(bounds.maxY()) >> shift;
            int minZ = Mth.floor(bounds.minZ()) >> shift, maxZ = Mth.floor(bounds.maxZ()) >> shift;
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        long position = WorldEngine.getWorldSectionId(level, x, y, z);
                        Section section;
                        if (add) {
                            section = sections.get(position);
                            if (section == null) {
                                int clipId = freeClipIds.isEmpty() ? nextClipId++ : freeClipIds.removeInt(
                                        freeClipIds.size() - 1);
                                section = new Section(position, clipId);
                                sections.put(position, section);
                                if (level == 0) {
                                    basePageYs.merge(y, 1, Integer::sum);
                                    basePages++;
                                }
                            }
                            section.members.computeIfAbsent(instance.templateId(), _ -> new IntOpenHashSet())
                                           .add(instance.slot());
                        } else {
                            section = sections.get(position);
                            var members = section.members.get(instance.templateId());
                            members.remove(instance.slot());
                            if (members.isEmpty()) section.members.remove(instance.templateId());
                            if (section.members.isEmpty()) {
                                sections.remove(position);
                                if (level == 0) {
                                    basePages--;
                                    int count = basePageYs.get(y) - 1;
                                    if (count == 0) basePageYs.remove(y);
                                    else basePageYs.put(y, count);
                                }
                            }
                        }
                        updateBounds(section, instance.bounds(), add);
                        dirtySections.add(section);
                        hierarchyDirty.add(position);
                        section.revision = membershipVersion;
                        if (level == 0) {
                            if (section.members.isEmpty()) removePending(section);
                            else addPending(section);
                        }
                    }
                }
        }
    }

    private void clearMembership() {
        long stamp = hierarchyLock.writeLock();
        try {
            clearMembershipUnlocked();
        } finally {
            hierarchyLock.unlockWrite(stamp);
        }
    }

    private static void updateBounds(Section section, FarStaticMeshes.Bounds bounds, boolean add) {
        int level = WorldEngine.getLevel(section.position);
        int extent = 32 << level;
        double scale = 1 << level;
        int x = WorldEngine.getX(section.position) * extent;
        int y = WorldEngine.getY(section.position) * extent;
        int z = WorldEngine.getZ(section.position) * extent;
        boolean first = section.boundMembers == 0;
        boolean last = section.boundMembers == 1;
        int xBounds = updateBoundAxis(section, 0,
                Mth.clamp(Mth.floor((bounds.minX() - x) / scale), 0, 31),
                Mth.clamp(Mth.ceil((bounds.maxX() - x) / scale) - 1, 0, 31), add, first, last);
        int yBounds = updateBoundAxis(section, 1,
                Mth.clamp(Mth.floor((bounds.minY() - y) / scale), 0, 31),
                Mth.clamp(Mth.ceil((bounds.maxY() - y) / scale) - 1, 0, 31), add, first, last);
        int zBounds = updateBoundAxis(section, 2,
                Mth.clamp(Mth.floor((bounds.minZ() - z) / scale), 0, 31),
                Mth.clamp(Mth.ceil((bounds.maxZ() - z) / scale) - 1, 0, 31), add, first, last);
        section.bounds = xBounds | yBounds | zBounds;
        section.boundMembers += add ? 1 : -1;
    }

    private static int updateBoundAxis(Section section, int axis, int lo, int hi, boolean add,
                                       boolean first, boolean last) {
        int minOffset = axis * 32;
        int maxOffset = (axis + 3) * 32;
        int delta = add ? 1 : -1;
        section.boundCounts[minOffset + lo] += delta;
        section.boundCounts[maxOffset + hi] += delta;
        if (!add && last) return 0;
        if (!first) {
            int minimum = boundMin(section.bounds, axis);
            int maximum = boundMax(section.bounds, axis);
            if (add) {
                lo = Math.min(lo, minimum);
                hi = Math.max(hi, maximum);
            } else {
                lo = minimum;
                hi = maximum;
                while (section.boundCounts[minOffset + lo] == 0) lo++;
                while (section.boundCounts[maxOffset + hi] == 0) hi--;
            }
        }
        return lo << axis * 5 | (hi - lo) << (15 + axis * 5);
    }

    private static int boundMin(int bounds, int axis) {
        return bounds >>> axis * 5 & 31;
    }

    private static int boundMax(int bounds, int axis) {
        return boundMin(bounds, axis) + (bounds >>> (15 + axis * 5) & 31);
    }

    private static int unionBounds(int first, int second) {
        int bounds = 0;
        for (int axis = 0; axis < 3; axis++) {
            int lo = Math.min(boundMin(first, axis), boundMin(second, axis));
            int hi = Math.max(boundMax(first, axis), boundMax(second, axis));
            bounds |= lo << axis * 5 | (hi - lo) << (15 + axis * 5);
        }
        return bounds;
    }

    private void clearMembershipUnlocked() {
        membershipVersion++;
        nativeRegistrationRevision = membershipVersion;
        nativeGeometryReady.clear();
        nativeGeometryRequired.clear();
        nativeGraphReady.clear();
        for (var section : sections.values()) {
            section.members.clear();
            dirtySections.add(section);
            hierarchyDirty.add(section.position);
        }
        sections.clear();
        basePageYs.clear();
        basePages = 0;
        mainBatches.clear();
        for (var page : List.copyOf(pendingPages)) removePending(page);
    }

    /**
     * Supplies render-only child presence without changing Voxy's persisted world voxels. Any host thread.
     */
    public byte children(long position) {
        long stamp = hierarchyLock.readLock();
        try {
            return childrenUnlocked(position);
        } finally {
            hierarchyLock.unlockRead(stamp);
        }
    }

    private byte childrenUnlocked(long position) {
        int level = WorldEngine.getLevel(position);
        if (level == 0) return sections.containsKey(position) ? (byte) -1 : 0;
        int x = WorldEngine.getX(position) << 1;
        int y = WorldEngine.getY(position) << 1;
        int z = WorldEngine.getZ(position) << 1;
        int mask = 0;
        for (int dy = 0; dy < 2; dy++)
            for (int dz = 0; dz < 2; dz++)
                for (int dx = 0; dx < 2; dx++) {
                    if (sections.containsKey(WorldEngine.getWorldSectionId(level - 1, x + dx, y + dy, z + dz))) {
                        mask |= 1 << WorldSection.getChildIndex(dx, dy, dz);
                    }
                }
        return (byte) mask;
    }

    /**
     * Native metadata owns selection even when the section has no ordinary terrain quads. Host worker thread.
     */
    public BuiltSection include(BuiltSection source) {
        long stamp = hierarchyLock.readLock();
        try {
            return includeUnlocked(source);
        } finally {
            hierarchyLock.unlockRead(stamp);
        }
    }

    private BuiltSection includeUnlocked(BuiltSection source) {
        var section = sections.get(source.position);
        preparedRevision = section == null ? 0 : section.revision;
        byte children = (byte) (source.childExistence | childrenUnlocked(source.position));
        if (!sections.containsKey(source.position)) {
            if (children == source.childExistence) return preparedSection = source;
            return preparedSection = new BuiltSection(source.position, children, source.aabb,
                    source.geometryBuffer, source.offsets, source.occupancy);
        }
        if (!source.isEmpty()) {
            return preparedSection = new BuiltSection(source.position, children,
                    unionBounds(source.aabb, section.bounds), source.geometryBuffer,
                    source.offsets, source.occupancy);
        }
        var allocation = new MemoryBuffer(8);
        MemoryUtil.memPutLong(allocation.address, 0);
        var offsets = new int[8];
        Arrays.fill(offsets, 1);
        return preparedSection = new BuiltSection(source.position, children, section.bounds, allocation, offsets,
                source.occupancy);
    }

    /**
     * Records the revision baked into a native allocation. Native node worker only.
     */
    public void nativeGeometry(int id, BuiltSection source) {
        if (preparedSection == source && preparedRevision != 0) {
            workerGeometry.put(id, new NativeGeometry(source.position, preparedRevision));
        }
    }

    /**
     * Native metadata is about to enter the worker's exact result packet.
     */
    public void nativeGeometryWritten(int id) {
        var geometry = workerGeometry.get(id);
        if (geometry != null && geometry.revision != 0) stageRevision(geometry.position, 0, geometry.revision);
    }

    public void nativeGeometryRemoved(int id) {
        var geometry = workerGeometry.remove(id);
        if (geometry != null) stageRevision(geometry.position, 2, geometry.revision);
    }

    /**
     * Supplies the actual native node state serialized with the packet, including requests still in flight.
     */
    public void nativeNode(long position, int geometryId, byte childMask, boolean inFlight) {
        long stamp = hierarchyLock.readLock();
        try {
            var section = sections.get(position);
            if (section == null) return;
            long revision = section.revision;
            workerNodeRevisions.put(position, revision);
            int required = Byte.toUnsignedInt(childrenUnlocked(position));
            boolean graphReady = !inFlight && (Byte.toUnsignedInt(childMask) & required) == required;
            stageRevision(position, graphReady ? 1 : 4, revision);
            var geometry = workerGeometry.get(geometryId);
            if (geometryId != -1 && (geometry == null || geometry.revision < revision)) {
                stageRevision(position, 3, revision);
            }
        } finally {
            hierarchyLock.unlockRead(stamp);
        }
    }

    private void stageRevision(long position, int kind, long revision) {
        if (kind == 1 || kind == 4 || kind == 5) {
            workerRevisions.remove(position | 1);
            workerRevisions.remove(position | 4);
            workerRevisions.remove(position | 5);
        } else {
            workerRevisions.remove(position);
            workerRevisions.remove(position | 2);
            workerRevisions.remove(position | 3);
        }
        workerRevisions.put(position | kind, revision);
    }

    /**
     * Logical removal excludes node-slot moves, which keep the same section in the native hierarchy.
     */
    public void nativeNodeRemoved(long position) {
        long revision = workerNodeRevisions.remove(position);
        if (revision != 0) stageRevision(position, 5, revision);
    }

    /**
     * Detached before the worker publishes the native SyncResults packet.
     */
    public Long2LongMap drainNativeRevisions() {
        if (workerRevisions.isEmpty()) return Long2LongMaps.EMPTY_MAP;
        var revisions = workerRevisions;
        workerRevisions = new Long2LongOpenHashMap();
        return revisions;
    }

    /**
     * Acknowledges only the packet whose GPU copies and node scatter writes completed in this native tick.
     */
    public void nativeApplied(Long2LongMap revisions) {
        var affected = new LongOpenHashSet();
        for (var entry : revisions.long2LongEntrySet()) {
            long position = entry.getLongKey() & ~15L;
            long revision = entry.getLongValue();
            if (revision < nativeRegistrationRevision) continue;
            switch ((int) entry.getLongKey() & 15) {
                case 0 -> {
                    nativeGeometryReady.put(position, revision);
                    nativeGeometryRequired.remove(position);
                }
                case 1 -> nativeGraphReady.put(position, revision);
                case 2 -> {
                    nativeGeometryReady.remove(position);
                    nativeGeometryRequired.remove(position);
                    queueBasePages(position);
                }
                case 3 -> nativeGeometryRequired.put(position, revision);
                case 4 -> nativeGraphReady.put(position, 0);
                case 5 -> {
                    nativeGraphReady.remove(position);
                    nativeGeometryRequired.remove(position);
                    queueBasePages(position);
                }
                default -> throw new IllegalArgumentException("Unknown native section publication");
            }
            affected.add(position);
        }
        for (var iter = affected.longIterator(); iter.hasNext(); ) retireBasePages(iter.nextLong());
    }

    private void addPending(Section page) {
        pendingPages.add(page);
        dirtyPendingPages.add(page);
        pendingCommandsDirty = true;
    }

    private void removePending(Section page) {
        pendingPages.remove(page);
        dirtyPendingPages.add(page);
        pendingCommandsDirty = true;
    }

    private void queueBasePages(long position) {
        visitBasePages(position, this::addPending);
    }

    private void retireBasePages(long position) {
        visitBasePages(position, page -> {
            if (pendingPages.contains(page) && nativeReady(page)) removePending(page);
        });
    }

    private void visitBasePages(long position, Consumer<Section> action) {
        int level = WorldEngine.getLevel(position);
        int span = 1 << level;
        int x0 = WorldEngine.getX(position) << level;
        int y0 = WorldEngine.getY(position) << level;
        int z0 = WorldEngine.getZ(position) << level;
        for (int y : basePageYs.subMap(y0, true, y0 + span, false).keySet()) {
            for (int z = z0; z < z0 + span; z++)
                for (int x = x0; x < x0 + span; x++) {
                    var page = sections.get(WorldEngine.getWorldSectionId(0, x, y, z));
                    if (page != null) action.accept(page);
                }
        }
    }

    private boolean nativeReady(Section page) {
        int x = WorldEngine.getX(page.position), y = WorldEngine.getY(page.position);
        int z = WorldEngine.getZ(page.position);
        boolean covered = false;
        for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            long position = WorldEngine.getWorldSectionId(level, x >> level, y >> level, z >> level);
            var section = sections.get(position);
            if (section == null) continue;
            long revision = section.revision;
            if (nativeGraphReady.containsKey(position) && nativeGraphReady.get(position) < revision) return false;
            if (nativeGeometryRequired.containsKey(position)) return false;
            if (nativeGeometryReady.containsKey(position) && nativeGeometryReady.get(position) < revision) return false;
            if (nativeGeometryReady.get(position) >= revision && nativeGraphReady.get(position) >= revision) {
                covered = true;
            }
        }
        return covered;
    }

    public void build(MDICViewport viewport, BasicSectionGeometryData geometry) {
        if (meshes == null || sections.isEmpty()) return;
        commandsReady = ensureProgram();
        if (!commandsReady) return;
        currentViewport = viewport;
        currentViewEpoch = SceneOcclusion.of(Minecraft.getInstance().level).frameEpoch();
        try (var _ = FarGlState.capture()) {
            GlCompat.pushDebugGroup("far/static_voxy_commands");
            try {
                uploadMembership();
                uploadPending();
                GL45C.glClearNamedBufferSubData(countBuffer.handle(), GL30C.GL_R32UI, 0,
                        (long) templateCapacity * 4, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT,
                        new int[]{0});
                GlStateManager._glUseProgram(commandProgram);
                GL30C.glUniform1ui(0, viewport.frameId);
                GL30C.glUniform1ui(1, hashWords.length / 4 - 1);
                GL30C.glUniform1ui(2, viewport.frameId + 1);
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, viewport.indirectLookupBuffer.id);
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, geometry.getMetadataBuffer().id);
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id);
                hashBuffer.bindStorage(3);
                groupBuffer.bindStorage(4);
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, meshes.templateBuffer());
                partitionBuffer.bindStorage(6);
                countBuffer.bindStorage(7);
                commandBuffer.bindStorage(8);
                coverageHeaders.bindStorage(9);
                coverageLinks.bindStorage(10);
                coverageFrames.bindStorage(11);
                GlStateManager._glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
                GL43C.glDispatchComputeIndirect(0);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
                if (pendingJobCount != 0) {
                    GlStateManager._glUseProgram(pendingProgram);
                    GL30C.glUniform1ui(0, viewport.frameId + 1);
                    GL30C.glUniform1ui(1, pendingJobCount);
                    pendingJobs.bindStorage(0);
                    coverageFrames.bindStorage(1);
                    pendingCommands.bindStorage(2);
                    GL43C.glDispatchCompute((pendingJobCount + 127) / 128, 1, 1);
                    GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
                }
            } finally {
                GlCompat.popDebugGroup();
            }
        }
    }

    public void draw(AbstractRenderPipeline pipeline, MDICViewport viewport) {
        if (!commandsReady || meshes == null || sections.isEmpty() || drawPackets.isEmpty()) return;
        var provider = VoxyStaticVisuals.provider(pipeline);
        if (provider == null || !provider.available(pipeline)) return;
        if (pipeline instanceof NormalRenderPipeline) pipeline.setupAndBindOpaque(viewport);
        try (var _ = FarGlState.capture()) {
            GlCompat.pushDebugGroup("far/static_voxy_draw");
            try {
                provider.draw(meshes, drawPackets, mappingBuffer.handle(), clipBuffer.handle(), pipeline, viewport);
            } finally {
                GlCompat.popDebugGroup();
            }
        }
    }

    public List<FarStaticDraw.DrawBatch> drawBatches() {
        return sections.isEmpty() ? List.of() : List.copyOf(drawPackets);
    }

    public List<FarStaticDraw.DrawBatch> nativeDrawBatches() {
        return sections.isEmpty() ? List.of() : List.copyOf(batches);
    }

    public List<FarStaticDraw.DrawBatch> pendingDrawBatches() {
        return sections.isEmpty() ? List.of() : List.copyOf(pendingBatches);
    }

    public int pendingPageCount() {
        return pendingPages.size();
    }

    public int basePageCount() {
        return basePages;
    }

    public List<FarStaticDraw.DrawBatch> mainDrawBatches() {
        return mainDrawEpoch == SceneOcclusion.of(Minecraft.getInstance().level).frameEpoch()
                ? List.copyOf(mainBatches) : List.of();
    }

    /**
     * Voxy's near mask can reject an entire section even when a captured mesh extends into vanilla. This handoff
     * uses occupied base pages around the camera and the main target's actual depth, independent of that mask.
     */
    public void drawMain(RenderContext context) {
        long epoch = SceneOcclusion.of(context.level()).frameEpoch();
        if (!commandsReady || currentViewEpoch != epoch || mainDrawEpoch == epoch || meshes == null || sections.isEmpty()
                || pipeline == null || currentViewport == null) return;
        var provider = VoxyStaticVisuals.provider(pipeline);
        if (provider == null || !provider.available(pipeline)) return;
        try (var _ = FarGlState.capture()) {
            GlCompat.pushDebugGroup("far/static_voxy_main");
            try {
                selectMainPages(context);
                if (mainBatches.isEmpty()) return;
                provider.drawMain(meshes, mainBatches, mappingBuffer.handle(), clipBuffer.handle(), pipeline,
                        currentViewport, context);
                mainDrawEpoch = epoch;
            } finally {
                GlCompat.popDebugGroup();
            }
        }
    }

    private void selectMainPages(RenderContext context) {
        var camera = context.camera().position();
        int chunkX = Mth.floor(camera.x) >> 4, chunkZ = Mth.floor(camera.z) >> 4;
        float radius = LodCompat.vanillaRadius();
        int radiusBits = Float.floatToIntBits(radius);
        if (mainMembershipVersion == membershipVersion && mainChunkX == chunkX && mainChunkZ == chunkZ
                && mainRadiusBits == radiusBits) return;
        mainMembershipVersion = membershipVersion;
        mainChunkX = chunkX;
        mainChunkZ = chunkZ;
        mainRadiusBits = radiusBits;
        int minX = Mth.floor((chunkX << 4) - radius) >> 5;
        int maxX = Mth.floor((chunkX << 4) + 16 + radius) >> 5;
        int minZ = Mth.floor((chunkZ << 4) - radius) >> 5;
        int maxZ = Mth.floor((chunkZ << 4) + 16 + radius) >> 5;
        var commands = new Int2ObjectOpenHashMap<IntArrayList>();
        for (int y : basePageYs.keySet())
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    var section = sections.get(WorldEngine.getWorldSectionId(0, x, y, z));
                    if (section == null) continue;
                    int firstInstance = section.mappingOffset;
                    for (var group : section.members.int2ObjectEntrySet()) {
                        var template = meshes.template(group.getIntKey());
                        var words = commands.computeIfAbsent(template.id(), _ -> new IntArrayList());
                        words.add(template.indexCount());
                        words.add(group.getValue().size());
                        words.add(template.firstIndex());
                        words.add(template.firstVertex());
                        words.add(firstInstance);
                        firstInstance += group.getValue().size();
                    }
                }
            }
        mainBatches.clear();
        if (commands.isEmpty()) return;
        int totalWords = 0;
        for (var words : commands.values()) totalWords += words.size();
        mainCommands.ensure((long) totalWords * 4);
        mainCounts.ensure((long) commands.size() * 4);
        var counts = new int[commands.size()];
        int firstWord = 0, counter = 0;
        for (var entry : commands.int2ObjectEntrySet()) {
            var words = entry.getValue();
            int count = words.size() / 5;
            mainCommands.write((long) firstWord * 4, words.elements(), words.size());
            counts[counter] = count;
            mainBatches.add(new FarStaticDraw.DrawBatch(entry.getIntKey(), mainCommands.handle(),
                    (long) firstWord * 4, mainCounts.handle(), (long) counter * 4, count));
            firstWord += words.size();
            counter++;
        }
        mainCounts.write(0, counts, counts.length);
    }

    private void uploadMembership() {
        if (dirtySections.isEmpty()) return;
        var dirtyTemplates = new IntOpenHashSet();
        for (var section : dirtySections) {
            for (int template : section.uploadedTemplates) {
                partitions.get(template).groups--;
                dirtyTemplates.add(template);
            }
            if (section.groupCount != 0) groupArena.free(section.groupOffset);
            if (section.mappingCount != 0) mappingArena.free(section.mappingOffset);
            section.groupCount = section.members.size();
            section.mappingCount = 0;
            for (var members : section.members.values()) section.mappingCount += members.size();
            section.uploadedTemplates = section.members.keySet().toIntArray();
            for (int template : section.uploadedTemplates) {
                var partition = partitions.computeIfAbsent(template, _ -> new Partition());
                partition.groups++;
                dirtyTemplates.add(template);
                templateCapacity = Math.max(templateCapacity, template + 1);
            }
            if (section.groupCount == 0) {
                removeHash(section.position);
                section.coverage.clear();
                dirtyCoverage.add(section);
                freeClipIds.add(section.clipId);
                continue;
            }
            section.groupOffset = (int) groupArena.alloc(section.groupCount);
            section.mappingOffset = (int) mappingArena.alloc(section.mappingCount);
            var groups = new int[section.groupCount * 4];
            var mappings = new int[section.mappingCount * 2];
            int groupAt = 0, mappingAt = 0;
            for (var group : section.members.int2ObjectEntrySet()) {
                groups[groupAt++] = group.getIntKey();
                groups[groupAt++] = section.mappingOffset + mappingAt / 2;
                groups[groupAt++] = group.getValue().size();
                groups[groupAt++] = section.clipId;
                for (var iter = group.getValue().intIterator(); iter.hasNext(); ) {
                    mappings[mappingAt++] = iter.nextInt();
                    mappings[mappingAt++] = section.clipId;
                }
            }
            groupBuffer.ensure(groupArena.getSize() * 16);
            groupBuffer.write((long) section.groupOffset * 16, groups, groups.length);
            mappingBuffer.ensure(mappingArena.getSize() * 8);
            mappingBuffer.write((long) section.mappingOffset * 8, mappings, mappings.length);
            int level = WorldEngine.getLevel(section.position);
            int extent = 32 << level;
            int x = WorldEngine.getX(section.position) * extent;
            int y = WorldEngine.getY(section.position) * extent;
            int z = WorldEngine.getZ(section.position) * extent;
            int[] clip = {x, y, z, 0, x + extent, y + extent, z + extent, 0};
            clipBuffer.ensure((long) (section.clipId + 1) * 32);
            clipBuffer.write((long) section.clipId * 32, clip, clip.length);
            coverageFrames.ensure((long) (section.clipId + 1) * 4);
            coverageFrames.write((long) section.clipId * 4, new int[]{0}, 1);
            // A page may hand off before its first GPU upload, so even an empty coverage header is published.
            dirtyCoverage.add(section);
            putHash(section.position, section.groupOffset, section.groupCount);
        }
        partitionBuffer.ensure((long) templateCapacity * 8);
        countBuffer.ensure((long) templateCapacity * 4);
        for (var iter = dirtyTemplates.intIterator(); iter.hasNext(); ) {
            int template = iter.nextInt();
            var partition = partitions.get(template);
            if (partition.groups > partition.capacity) {
                if (partition.capacity != 0) commandArena.free(partition.first);
                partition.capacity = Integer.highestOneBit(partition.groups - 1) << 1;
                if (partition.capacity == 0) partition.capacity = 1;
                partition.first = (int) commandArena.alloc(partition.capacity);
            }
            int[] words = {partition.first, partition.capacity};
            partitionBuffer.write((long) template * 8, words, words.length);
        }
        commandBuffer.ensure(commandArena.getSize() * 20);
        batches.clear();
        for (var entry : partitions.int2ObjectEntrySet()) {
            var partition = entry.getValue();
            if (partition.groups != 0) {
                batches.add(new FarStaticDraw.DrawBatch(entry.getIntKey(), commandBuffer.handle(),
                        (long) partition.first * 20, countBuffer.handle(), (long) entry.getIntKey() * 4,
                        partition.groups));
            }
        }
        uploadHash();
        dirtySections.clear();
        packetsDirty = true;
    }

    private void uploadPending() {
        for (var page : dirtyPendingPages) {
            int x = WorldEngine.getX(page.position), y = WorldEngine.getY(page.position);
            int z = WorldEngine.getZ(page.position);
            boolean pending = pendingPages.contains(page) && sections.get(page.position) == page;
            for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                var section = sections.get(WorldEngine.getWorldSectionId(level, x >> level, y >> level, z >> level));
                if (section == null) continue;
                boolean changed = pending ? section.coverage.add(page.clipId) : section.coverage.remove(page.clipId);
                if (changed) dirtyCoverage.add(section);
            }
        }
        dirtyPendingPages.clear();
        coverageLinks.ensure(4);
        for (var section : dirtyCoverage) {
            if (section.coverageCount != 0) coverageArena.free(section.coverageOffset);
            section.coverageCount = section.coverage.size();
            if (section.coverageCount != 0) {
                section.coverageOffset = (int) coverageArena.alloc(section.coverageCount);
                int[] links = section.coverage.toIntArray();
                coverageLinks.ensure(coverageArena.getSize() * 4);
                coverageLinks.write((long) section.coverageOffset * 4, links, links.length);
            }
            int[] header = {section.coverageOffset, section.coverageCount};
            coverageHeaders.ensure((long) (section.clipId + 1) * 8);
            coverageHeaders.write((long) section.clipId * 8, header, header.length);
        }
        dirtyCoverage.clear();
        if (pendingCommandsDirty) {
            var grouped = new Int2ObjectOpenHashMap<List<PendingGroup>>();
            pendingJobCount = 0;
            for (var page : pendingPages) {
                int first = page.mappingOffset;
                for (var group : page.members.int2ObjectEntrySet()) {
                    grouped.computeIfAbsent(group.getIntKey(), _ -> new ArrayList<>())
                           .add(new PendingGroup(first, group.getValue().size(), page.clipId));
                    first += group.getValue().size();
                    pendingJobCount++;
                }
            }
            pendingBatches.clear();
            if (pendingJobCount != 0) {
                var commandWords = new int[pendingJobCount * 5];
                var jobWords = new int[pendingJobCount * 4];
                pendingCommands.ensure((long) commandWords.length * 4);
                pendingJobs.ensure((long) jobWords.length * 4);
                int command = 0;
                for (var entry : grouped.int2ObjectEntrySet()) {
                    var template = meshes.template(entry.getIntKey());
                    int first = command;
                    for (var group : entry.getValue()) {
                        int at = command * 5;
                        commandWords[at] = template.indexCount();
                        commandWords[at + 2] = template.firstIndex();
                        commandWords[at + 3] = template.firstVertex();
                        commandWords[at + 4] = group.firstInstance;
                        at = command * 4;
                        jobWords[at] = group.firstInstance;
                        jobWords[at + 1] = group.instances;
                        jobWords[at + 2] = group.clip;
                        jobWords[at + 3] = command++;
                    }
                    pendingBatches.add(new FarStaticDraw.DrawBatch(entry.getIntKey(), pendingCommands.handle(),
                            (long) first * 20, 0, 0, command - first));
                }
                pendingCommands.write(0, commandWords, commandWords.length);
                pendingJobs.write(0, jobWords, jobWords.length);
            }
            pendingCommandsDirty = false;
            packetsDirty = true;
        }
        if (packetsDirty) {
            drawPackets.clear();
            drawPackets.addAll(batches);
            drawPackets.addAll(pendingBatches);
            packetsDirty = false;
        }
    }

    private static int hash(long key) {
        int value = (int) key ^ (int) (key >>> 32);
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private void putHash(long position, int first, int count) {
        if ((hashUsed + 1) * 2 >= hashWords.length / 4) {
            int size = (hashEntries + 1) * 2 >= hashWords.length / 4 ? hashWords.length * 2 : hashWords.length;
            hashWords = new int[size];
            hashEntries = 0;
            hashUsed = 0;
            hashRebuild = true;
            for (var section : sections.values()) {
                if (section.groupCount != 0) insertHash(section.position, section.groupOffset, section.groupCount);
            }
        }
        int at = insertHash(position, first, count);
        if (!hashRebuild) hashBuffer.write((long) at * 4, Arrays.copyOfRange(hashWords, at, at + 4), 4);
    }

    private int insertHash(long position, int first, int count) {
        int mask = hashWords.length / 4 - 1;
        int slot = hash(position) & mask;
        int tombstone = -1;
        while (true) {
            int at = slot * 4;
            int state = hashWords[at + 3];
            if (state == 0) {
                if (tombstone != -1) at = tombstone;
                else hashUsed++;
                hashWords[at] = (int) position;
                hashWords[at + 1] = (int) (position >>> 32);
                hashWords[at + 2] = first;
                hashWords[at + 3] = count;
                hashEntries++;
                return at;
            }
            if (state == TOMBSTONE) {
                if (tombstone == -1) tombstone = at;
            } else if (hashWords[at] == (int) position && hashWords[at + 1] == (int) (position >>> 32)) {
                hashWords[at + 2] = first;
                hashWords[at + 3] = count;
                return at;
            }
            slot = slot + 1 & mask;
        }
    }

    private void removeHash(long position) {
        int mask = hashWords.length / 4 - 1;
        int slot = hash(position) & mask;
        while (true) {
            int at = slot * 4;
            if (hashWords[at + 3] == 0) return;
            if (hashWords[at] == (int) position && hashWords[at + 1] == (int) (position >>> 32)
                    && hashWords[at + 3] != TOMBSTONE) {
                hashWords[at + 3] = TOMBSTONE;
                hashEntries--;
                if (!hashRebuild) hashBuffer.write((long) (at + 3) * 4, new int[]{TOMBSTONE}, 1);
                return;
            }
            slot = slot + 1 & mask;
        }
    }

    private void uploadHash() {
        if (!hashRebuild) return;
        hashBuffer.ensure((long) hashWords.length * 4);
        hashBuffer.write(0, hashWords, hashWords.length);
        hashRebuild = false;
    }

    private boolean ensureProgram() {
        if (programSources != FlwPrograms.SOURCES) {
            if (programSources != null) closeDrawProvider();
            if (commandBuild != null) commandBuild.close();
            if (pendingBuild != null) pendingBuild.close();
            programSources = FlwPrograms.SOURCES;
            commandBuild = compileCommands("voxy_static_commands", "voxy_commands");
            pendingBuild = compileCommands("voxy_static_pending", "voxy_pending");
        }
        var command = commandBuild.get();
        var pending = pendingBuild.get();
        if (command == null || pending == null) return false;
        commandProgram = command.handle();
        pendingProgram = pending.handle();
        return true;
    }

    private AsyncGlProgram compileCommands(String resource, String name) {
        var source = new Compilation();
        source.version(GlslVersion.V430);
        source.appendComponent(programSources.get(ResourceUtil.rl("internal/far/" + resource + ".comp")));
        return AsyncGlProgram.compute("far_static/" + name, source, _ -> {
        });
    }

    @Override
    public void close() {
        if (meshes != null) meshes.removeListener(listener);
        closeDrawProvider();
        hashBuffer.delete();
        groupBuffer.delete();
        mappingBuffer.delete();
        clipBuffer.delete();
        partitionBuffer.delete();
        countBuffer.delete();
        commandBuffer.delete();
        mainCommands.delete();
        mainCounts.delete();
        coverageHeaders.delete();
        coverageLinks.delete();
        coverageFrames.delete();
        pendingJobs.delete();
        pendingCommands.delete();
        if (commandBuild != null) commandBuild.close();
        if (pendingBuild != null) pendingBuild.close();
    }

    private void closeDrawProvider() {
        if (pipeline == null) return;
        var provider = VoxyStaticVisuals.provider(pipeline);
        if (provider != null) provider.close(pipeline);
    }

    private static final class Section {
        final long position;
        final int clipId;
        final Int2ObjectOpenHashMap<IntOpenHashSet> members = new Int2ObjectOpenHashMap<>();
        int[] uploadedTemplates = new int[0];
        int groupOffset;
        int groupCount;
        int mappingOffset;
        int mappingCount;
        long revision;
        final int[] boundCounts = new int[6 * 32];
        int boundMembers;
        int bounds;
        final IntOpenHashSet coverage = new IntOpenHashSet();
        int coverageOffset;
        int coverageCount;

        Section(long position, int clipId) {
            this.position = position;
            this.clipId = clipId;
        }
    }

    private static final class Partition {
        int groups;
        int first;
        int capacity;
    }

    private record NativeGeometry(long position, long revision) {
    }

    private record PendingGroup(int firstInstance, int instances, int clip) {
    }
}

#endif
