package dev.engine_room.flywheel.impl.compat;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.far.FarBuffer;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.engine.far.FarRanges;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Frozen visual geometry attached to DH's section hierarchy. Capture changes update section membership once;
 * rendering consumes the sections DH already selected. All access and GPU ownership are on the render thread.
 */
public final class DhStaticVisuals {
    private static final Map<FarStaticMeshes, Sections> SOURCES = new IdentityHashMap<>();
    private static DrawProvider nativeDraw;
    private static DrawProvider packDraw;
    private static Submitted submitted = new Submitted(0, 0, 0);

    private DhStaticVisuals() {
    }

    /**
     * Registers a direct host draw provider before captures can be handed to DH. Render thread.
     */
    public static void registerNative(DrawProvider provider) {
        nativeDraw = provider;
    }

    /**
     * Registers the active shader API's direct DH material program. Render thread.
     */
    public static void registerShaderpack(DrawProvider provider) {
        packDraw = provider;
    }

    public static boolean available() {
        if (!DistantHorizonsCompat.ACTIVE || VkContext.isVulkanHost()) return false;
        var provider = ShadersModHelper.isShaderPackInUse() ? packDraw : nativeDraw;
        return provider != null && provider.available();
    }

    /**
     * Called synchronously after a captured entry changes; a null pair releases this source's section buffers.
     */
    public static void changed(FarStaticMeshes meshes, FarStaticMeshes.Entry before, FarStaticMeshes.Entry after) {
        if (!DistantHorizonsCompat.ACTIVE) return;
        if (before == null && after == null) {
            var sections = SOURCES.remove(meshes);
            if (sections != null) sections.close();
            if (SOURCES.isEmpty()) closeProviders();
            return;
        }
        var sections = SOURCES.computeIfAbsent(meshes, _ -> new Sections());
        if (before != null) sections.update(before, false);
        if (after != null) sections.update(after, true);
    }

    /**
     * Clears the previous host selection before this frame's LoD renderer can supply its current view.
     */
    public static void beginFrame(FarStaticMeshes meshes) {
        submitted = new Submitted(0, 0, 0);
        var sections = SOURCES.get(meshes);
        if (sections != null) {
            sections.selectedThisFrame = false;
            sections.mainPrepared = false;
        }
    }

    /**
     * Releases section mappings and provider-owned GPU state before the device closes. Render thread.
     */
    public static void destroyGpuResources() {
        SOURCES.values().forEach(Sections::close);
        SOURCES.clear();
        closeProviders();
        submitted = new Submitted(0, 0, 0);
    }

    private static void closeProviders() {
        if (nativeDraw != null) nativeDraw.close();
        if (packDraw != null) packDraw.close();
    }

    /**
     * DH opaque terrain is complete; direct static geometry joins it before the retained dynamic-depth capture.
     */
    public static void draw(RenderParams params, RenderBufferHandler buffers) {
        if (GuestTerrainGate.renderingShadow()) return;
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        var far = manager == null ? null : manager.farVisuals();
        var meshes = far == null ? null : far.staticMeshes();
        var sections = SOURCES.get(meshes);
        var provider = ShadersModHelper.isShaderPackInUse() ? packDraw : nativeDraw;
        if (sections == null || provider == null || !provider.available()) return;
        var context = manager.currentFrameContext();
        var batches = sections.selected(meshes, buffers.flywheel$staticSections(), context);
        if (far.nearVanilla() && !sections.selectedMain(meshes, context).isEmpty()) {
            // Native water writes this same texture before the main draw. Preserve it before our own geometry too.
            provider.prepareMain(DistantHorizonsCompat.farHost(params), context);
            sections.mainPrepared = true;
        }
        if (batches.isEmpty()) return;
        GlCompat.pushDebugGroup("far/dh/static");
        try (var state = FarGlState.capture()) {
            provider.draw(meshes, batches, params);
            recordSubmitted(batches);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    /**
     * Retained static geometry near vanilla also writes the main scene after vanilla opaque terrain.
     */
    public static void drawMain(RenderContext context) {
        if (!DistantHorizonsCompat.ACTIVE || GuestTerrainGate.renderingShadow()) return;
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        var far = manager == null ? null : manager.farVisuals();
        if (far == null || !far.nearVanilla()) return;
        var meshes = far.staticMeshes();
        var sections = SOURCES.get(meshes);
        var provider = ShadersModHelper.isShaderPackInUse() ? packDraw : nativeDraw;
        if (sections == null || !sections.selectedThisFrame || !sections.mainPrepared
                || provider == null || !provider.available()) return;
        var batches = sections.selectedMain(meshes, context);
        if (batches.isEmpty()) return;
        GlCompat.pushDebugGroup("far/dh/static_main");
        try (var state = FarGlState.capture()) {
            provider.drawMain(meshes, batches, context);
            recordSubmitted(batches);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    /**
     * Cached indirect commands sharing one template and the source's global mapping/clip buffers.
     */
    public record Batch(FarStaticMeshes.Template template, int mappingBuffer, int clipBuffer,
                        FarStaticDraw.DrawBatch commands, long instances, long indices) {
    }

    /**
     * Commands submitted by the direct host and near passes in the current frame.
     */
    public static Submitted submitted() {
        return submitted;
    }

    public record Submitted(long draws, long instances, long indices) {
    }

    /**
     * On-demand cache diagnostics; counters change only when a command list or membership upload is rebuilt.
     */
    public static CacheStats cacheStats() {
        long nativeBuilds = 0, mainBuilds = 0, membershipUploads = 0;
        int nativeCommands = 0, mainCommands = 0, pages = 0;
        for (var sections : SOURCES.values()) {
            nativeBuilds += sections.selectedBuilds;
            mainBuilds += sections.mainBuilds;
            membershipUploads += sections.membershipUploads;
            nativeCommands += sections.selected.commandCount;
            mainCommands += sections.main.commandCount;
            pages += sections.pages.size();
        }
        return new CacheStats(nativeBuilds, mainBuilds, membershipUploads, nativeCommands, mainCommands, pages);
    }

    public record CacheStats(long nativeBuilds, long mainBuilds, long membershipUploads,
                             int nativeCommands, int mainCommands, int pages) {
    }

    private static void recordSubmitted(List<Batch> batches) {
        long instances = 0;
        long indices = 0;
        long draws = 0;
        for (var batch : batches) {
            instances += batch.instances();
            indices += batch.indices();
            draws += batch.commands().maxDrawCount();
        }
        submitted = new Submitted(submitted.draws() + draws, submitted.instances() + instances,
                submitted.indices() + indices);
    }

    public interface DrawProvider {
        boolean available();

        /**
         * Prepares the current opaque source only for a nonempty cached main selection, without changing bindings.
         */
        void prepareMain(FarHost host, RenderContext context);

        void draw(FarStaticMeshes meshes, List<Batch> batches, RenderParams params);

        void drawMain(FarStaticMeshes meshes, List<Batch> batches, RenderContext context);

        default void close() {
        }
    }

    private static final class Sections implements AutoCloseable {
        private final Long2ObjectOpenHashMap<Page> pages = new Long2ObjectOpenHashMap<>();
        private final LinkedHashSet<Page> dirtyPages = new LinkedHashSet<>();
        private final FarRanges mappingRanges = new FarRanges();
        private final FarRanges clipRanges = new FarRanges();
        private final FarBuffer mappings = new FarBuffer();
        private final FarBuffer clips = new FarBuffer();
        private final Commands selected = new Commands();
        private final Commands main = new Commands();
        private final LongOpenHashSet selectedPositions = new LongOpenHashSet();
        private long membershipVersion;
        private long selectedVersion = -1;
        private long mainVersion = -1;
        private long selectedBuilds;
        private long mainBuilds;
        private long membershipUploads;
        private int selectedBlockX;
        private int selectedYSlice;
        private int selectedBlockZ;
        private int mainBlockX;
        private int mainYSlice;
        private int mainBlockZ;
        private int mainRadiusBits;
        private boolean selectedThisFrame;
        private boolean mainPrepared;

        void update(FarStaticMeshes.Entry entry, boolean add) {
            membershipVersion++;
            for (var instance : entry.instances()) {
                var bounds = instance.bounds();
                for (byte detail = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
                     detail <= FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL; detail++) {
                    int width = 1 << detail;
                    int minX = (int) Math.floor(bounds.minX() / width);
                    int maxX = (int) Math.floor(bounds.maxX() / width);
                    int minZ = (int) Math.floor(bounds.minZ() / width);
                    int maxZ = (int) Math.floor(bounds.maxZ() / width);
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            long pos = DhSectionPos.encode(detail, x, z);
                            var page = pages.get(pos);
                            if (add && page == null) {
                                page = new Page(clipRanges.allocate(1), x * width, z * width,
                                        (x + 1) * width, (z + 1) * width);
                                pages.put(pos, page);
                                clips.ensure((long) clipRanges.size() * 32);
                                clips.write((long) page.clipId * 32, new int[]{x * width, -1_000_000_000,
                                        z * width, 0, (x + 1) * width, 1_000_000_000, (z + 1) * width, 0}, 8);
                            }
                            page.update(instance.templateId(), instance.slot(), add);
                            if (page.instances.isEmpty()) {
                                pages.remove(pos);
                                dirtyPages.remove(page);
                                if (page.mappingCount != 0) mappingRanges.free(page.mappingOffset, page.mappingCount);
                                clipRanges.free(page.clipId, 1);
                            } else {
                                dirtyPages.add(page);
                            }
                        }
                    }
                }
            }
        }

        List<Batch> selected(FarStaticMeshes meshes, LongList positions, RenderContext context) {
            selectedThisFrame = true;
            uploadMembership(meshes);
            var camera = context.camera().position();
            int blockX = Mth.floor(camera.x);
            int ySlice = Mth.floor(camera.y * 16.0);
            int blockZ = Mth.floor(camera.z);
            if (selectedVersion == membershipVersion && selectedBlockX == blockX && selectedYSlice == ySlice
                    && selectedBlockZ == blockZ && sameSelection(positions)) return selected.batches;
            selectedVersion = membershipVersion;
            selectedBlockX = blockX;
            selectedYSlice = ySlice;
            selectedBlockZ = blockZ;
            selectedBuilds++;
            selectedPositions.clear();
            selectedPositions.addAll(positions);
            selected.begin();
            for (int i = 0; i < positions.size(); i++) {
                var page = pages.get(positions.getLong(i));
                if (page != null) selected.add(page, meshes, blockX, ySlice, blockZ);
            }
            selected.upload(meshes, mappings.handle(), clips.handle());
            return selected.batches;
        }

        private boolean sameSelection(LongList positions) {
            if (selectedPositions.size() != positions.size()) return false;
            for (int i = 0; i < positions.size(); i++) {
                if (!selectedPositions.contains(positions.getLong(i))) return false;
            }
            return true;
        }

        List<Batch> selectedMain(FarStaticMeshes meshes, RenderContext context) {
            var camera = context.camera().position();
            int blockX = Mth.floor(camera.x);
            int ySlice = Mth.floor(camera.y * 16.0);
            int blockZ = Mth.floor(camera.z);
            float radius = LodCompat.vanillaRadius();
            int radiusBits = Float.floatToIntBits(radius);
            if (mainVersion == membershipVersion && mainBlockX == blockX && mainYSlice == ySlice && mainBlockZ == blockZ
                    && mainRadiusBits == radiusBits) return main.batches;
            mainVersion = membershipVersion;
            mainBlockX = blockX;
            mainYSlice = ySlice;
            mainBlockZ = blockZ;
            mainRadiusBits = radiusBits;
            mainBuilds++;
            main.begin();
            int cameraMinX = blockX, cameraMaxX = blockX + 1;
            int cameraMinZ = blockZ, cameraMaxZ = blockZ + 1;
            int minX = Mth.floor(cameraMinX - radius) >> DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            int maxX = Mth.floor(cameraMaxX + radius) >> DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            int minZ = Mth.floor(cameraMinZ - radius) >> DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            int maxZ = Mth.floor(cameraMaxZ + radius) >> DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            int width = 1 << DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = Math.max(0, Math.max(z * width - cameraMaxZ, cameraMinZ - (z + 1) * width));
                for (int x = minX; x <= maxX; x++) {
                    double dx = Math.max(0, Math.max(x * width - cameraMaxX, cameraMinX - (x + 1) * width));
                    if (dx * dx + dz * dz > radius * radius) continue;
                    var page = pages.get(DhSectionPos.encode(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, x, z));
                    if (page != null) main.add(page, meshes, blockX, ySlice, blockZ);
                }
            }
            main.upload(meshes, mappings.handle(), clips.handle());
            return main.batches;
        }

        private void uploadMembership(FarStaticMeshes meshes) {
            if (dirtyPages.isEmpty()) return;
            membershipUploads++;
            for (var page : dirtyPages) {
                if (page.mappingCount != 0) mappingRanges.free(page.mappingOffset, page.mappingCount);
                page.mappingCount = page.instances.values().stream().mapToInt(IntOpenHashSet::size).sum();
                page.mappingOffset = mappingRanges.allocate(page.mappingCount);
            }
            mappings.ensure((long) mappingRanges.size() * 8);
            for (var page : dirtyPages) {
                int[] words = new int[page.mappingCount * 2];
                int index = 0;
                page.groups.clear();
                for (var entry : page.instances.int2ObjectEntrySet()) {
                    int first = page.mappingOffset + index / 2;
                    int[] slots = entry.getValue().toIntArray();
                    Arrays.sort(slots);
                    double minAnchorX = Double.POSITIVE_INFINITY, minAnchorY = Double.POSITIVE_INFINITY;
                    double minAnchorZ = Double.POSITIVE_INFINITY;
                    double maxAnchorX = Double.NEGATIVE_INFINITY, maxAnchorY = Double.NEGATIVE_INFINITY;
                    double maxAnchorZ = Double.NEGATIVE_INFINITY;
                    for (int slot : slots) {
                        var instance = meshes.instance(slot);
                        var bounds = instance.bounds();
                        words[index++] = slot;
                        // The high bit skips clipping only when the complete captured bounds fit this page.
                        words[index++] = page.clipId | (page.contains(bounds)
                                ? Integer.MIN_VALUE : 0);
                        long anchor = instance.pos();
                        int x = BlockPos.getX(anchor), y = BlockPos.getY(anchor), z = BlockPos.getZ(anchor);
                        minAnchorX = Math.min(minAnchorX, x);
                        minAnchorY = Math.min(minAnchorY, y);
                        minAnchorZ = Math.min(minAnchorZ, z);
                        maxAnchorX = Math.max(maxAnchorX, x);
                        maxAnchorY = Math.max(maxAnchorY, y);
                        maxAnchorZ = Math.max(maxAnchorZ, z);
                    }
                    page.groups.add(new Group(entry.getIntKey(), first, entry.getValue().size(),
                            minAnchorX, minAnchorY, minAnchorZ, maxAnchorX, maxAnchorY, maxAnchorZ));
                }
                mappings.write((long) page.mappingOffset * 8, words, words.length);
            }
            dirtyPages.clear();
        }

        @Override
        public void close() {
            pages.clear();
            dirtyPages.clear();
            selectedPositions.clear();
            mappings.delete();
            clips.delete();
            selected.close();
            main.close();
            mappingRanges.clear();
            clipRanges.clear();
        }
    }

    private static final class Page {
        private final Int2ObjectOpenHashMap<IntOpenHashSet> instances = new Int2ObjectOpenHashMap<>();
        private final List<Group> groups = new ArrayList<>();
        private final int clipId;
        private final int clipMinX;
        private final int clipMinZ;
        private final int clipMaxX;
        private final int clipMaxZ;
        private int mappingOffset;
        private int mappingCount;

        private Page(int clipId, int clipMinX, int clipMinZ, int clipMaxX, int clipMaxZ) {
            this.clipId = clipId;
            this.clipMinX = clipMinX;
            this.clipMinZ = clipMinZ;
            this.clipMaxX = clipMaxX;
            this.clipMaxZ = clipMaxZ;
        }

        boolean contains(FarStaticMeshes.Bounds bounds) {
            return bounds.minX() >= clipMinX && bounds.maxX() < clipMaxX
                    && bounds.minY() >= -1_000_000_000 && bounds.maxY() < 1_000_000_000
                    && bounds.minZ() >= clipMinZ && bounds.maxZ() < clipMaxZ;
        }

        void update(int template, int slot, boolean add) {
            var slots = add ? instances.computeIfAbsent(template, _ -> new IntOpenHashSet()) : instances.get(template);
            if (add) slots.add(slot);
            else slots.remove(slot);
            if (slots.isEmpty()) instances.remove(template);
        }
    }

    private record Group(int template, int firstInstance, int instanceCount,
                         double minAnchorX, double minAnchorY, double minAnchorZ,
                         double maxAnchorX, double maxAnchorY, double maxAnchorZ) {
        int facingMask(FarStaticMeshes.Template template, int blockX, int ySlice, int blockZ) {
            if (!template.material().backfaceCulling()) return 63;
            double minViewX = blockX - maxAnchorX, maxViewX = blockX + 1.0 - minAnchorX;
            double minViewY = ySlice / 16.0 - maxAnchorY, maxViewY = (ySlice + 1.0) / 16.0 - minAnchorY;
            double minViewZ = blockZ - maxAnchorZ, maxViewZ = blockZ + 1.0 - minAnchorZ;
            var normals = template.normalBounds();
            var counts = template.normalIndexCounts();
            var planes = template.normalPlaneOffsets();
            int mask = 0;
            for (int group = 0; group < 6; group++) {
                if (counts[group] == 0) continue;
                int at = group * 6;
                double maximum = maximumProduct(normals[at], normals[at + 3], minViewX, maxViewX)
                        + maximumProduct(normals[at + 1], normals[at + 4], minViewY, maxViewY)
                        + maximumProduct(normals[at + 2], normals[at + 5], minViewZ, maxViewZ);
                if (maximum > planes[group]) mask |= 1 << group;
            }
            return mask;
        }

        private static double maximumProduct(float minNormal, float maxNormal, double minView, double maxView) {
            return Math.max(Math.max(minNormal * minView, minNormal * maxView),
                    Math.max(maxNormal * minView, maxNormal * maxView));
        }

    }

    private static final class Commands implements AutoCloseable {
        private final FarBuffer buffer = new FarBuffer();
        private final Int2ObjectOpenHashMap<IntArrayList> words = new Int2ObjectOpenHashMap<>();
        private final Int2LongOpenHashMap instances = new Int2LongOpenHashMap();
        private final Int2LongOpenHashMap indices = new Int2LongOpenHashMap();
        private final List<Batch> batches = new ArrayList<>();
        private int commandCount;

        void begin() {
            words.clear();
            instances.clear();
            indices.clear();
            batches.clear();
            commandCount = 0;
        }

        void add(Page page, FarStaticMeshes meshes, int blockX, int ySlice, int blockZ) {
            for (var group : page.groups) {
                var template = meshes.template(group.template());
                var variant = template.variant(group.facingMask(template, blockX, ySlice, blockZ));
                if (variant.indexCount() == 0) continue;
                var commands = words.computeIfAbsent(template.id(), _ -> new IntArrayList());
                commands.add(variant.indexCount());
                commands.add(group.instanceCount());
                commands.add(variant.firstIndex());
                commands.add(template.firstVertex());
                commands.add(group.firstInstance());
                instances.addTo(template.id(), group.instanceCount());
                indices.addTo(template.id(), (long) variant.indexCount() * group.instanceCount());
            }
        }

        void upload(FarStaticMeshes meshes, int mapping, int clip) {
            if (words.isEmpty()) return;
            int count = words.values().stream().mapToInt(IntArrayList::size).sum();
            commandCount = count / 5;
            buffer.ensure((long) count * Integer.BYTES);
            int first = 0;
            for (var entry : words.int2ObjectEntrySet()) {
                int template = entry.getIntKey();
                var data = entry.getValue();
                long offset = (long) first * Integer.BYTES;
                buffer.write(offset, data.elements(), data.size());
                var commands = new FarStaticDraw.DrawBatch(template, buffer.handle(), offset, 0, 0,
                        data.size() / 5);
                batches.add(new Batch(meshes.template(template), mapping, clip, commands, instances.get(template),
                        indices.get(template)));
                first += data.size();
            }
        }

        @Override
        public void close() {
            buffer.delete();
            begin();
        }
    }
}
