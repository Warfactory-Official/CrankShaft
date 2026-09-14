package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.function.IntConsumer;

public final class TerrainSectionRegistry implements TerrainSectionListener {
    static final int SECTION_DATA_STRIDE = 48;
    static final int REGION_SIZE = RenderRegion.REGION_SIZE;
    static final int GEOMETRY_MASK_WORDS = REGION_SIZE / Integer.SIZE;
    private static final int PENDING_WORDS = REGION_SIZE / Long.SIZE;
    static final int VISIBILITY_STRIDE = Integer.BYTES;
    static final int PASS_SOLID = 0;
    static final int PASS_CUTOUT = 1;
    static final int PASS_COUNT = 2;

    private static final int METADATA_REGION_ID_CAP = 65_536;
    private static final int FLOAT_ONE_BITS = Float.floatToRawIntBits(1.0f);
    private final TerrainResidentBuffers buffers;
    /**
     * Persistent region-id-keyed SectionRenderData mirror (indexed {@code regionId * REGION_SIZE + s}), bound at
     * binding 1 (SectionData).
     */
    private final TerrainResidentBuffer[] sectionDataMirrors;
    private final long[] sectionDataMirrorBytes = new long[PASS_COUNT];
    private final TerrainResidentBuffer[] presentMaskBuffers;
    // Never cleared: phase 1 writes all 256 entries of every batched region before any reader.
    private final TerrainResidentBuffer[] sectionVisBuffers;
    private final TerrainResidentBuffer translucentSectionDataMirror;
    private final TerrainResidentBuffer translucentVisBuffer;

    /**
     * ACTIVE mid-fade sections only (key {@code regionId<<8 | s} == the vis buffer index).
     */
    private final Long2LongMap activeFadeFirstSeen = new Long2LongOpenHashMap();
    private final Long2LongMap activeFadeDuration = new Long2LongOpenHashMap();
    private final MemoryBlock visScratch = MemoryBlock.malloc(Float.BYTES);
    private final MemoryBlock slotScratch = MemoryBlock.malloc(Integer.BYTES);
    private int[][] presentMaskShadow = {new int[0], new int[0]};
    private int presentMaskRegionCap = 0;
    private long sectionVisBufferSize = 0;
    private long translucentSectionDataMirrorBytes = 0;
    private int[] translucentPresentMaskShadow = new int[0];
    private int[] translucentRegionMaxIndexCount = new int[0];
    private int[] translucentRegionIndexCountSum = new int[0];
    private int[] translucentSectionIndexCount = new int[0];
    private long translucentVisBufferSize = 0;
    private int regionTableCap = 0;
    private int[] originChunkX = new int[0];
    private int[] originChunkY = new int[0];
    private int[] originChunkZ = new int[0];
    private boolean[] live = new boolean[0];
    private int[] geometryHandle = new int[0];
    private int[][] regionMaxIndexCount = {new int[0], new int[0]};
    // Slot bits [regionId * REGION_SIZE + s]: may be nonzero. Clear only dirty slots: refeeds re-clear every empty
    // pass (~6k redundant GL clears/s in flight). Translucent cull decodes all 256 slots ungated => never skip a
    // dirty clear.
    private long[][] sectionDataDirty = {new long[0], new long[0]};
    private long[] translucentDataDirty = new long[0];
    // Hooks mark, flush feeds once: each defrag/transfer notify re-marks (up to 3 region notifies/move), ~19x uploads.
    private @Nullable RenderRegion[] pendingRegion = new RenderRegion[0];
    private long[] pendingSections = new long[0];
    private final IntArrayList pendingRegionIds = new IntArrayList();
    @Nullable
    private IntConsumer regionFreedListener;

    public TerrainSectionRegistry(TerrainResidentBuffers buffers) {
        this.buffers = buffers;
        this.sectionDataMirrors = new TerrainResidentBuffer[]{buffers.createMirror(
                SECTION_DATA_STRIDE), buffers.createMirror(SECTION_DATA_STRIDE)};
        this.presentMaskBuffers = new TerrainResidentBuffer[]{buffers.createDynamic(), buffers.createDynamic()};
        this.sectionVisBuffers = new TerrainResidentBuffer[]{buffers.createDynamic(), buffers.createDynamic()};
        this.translucentSectionDataMirror = buffers.createMirror(SECTION_DATA_STRIDE);
        this.translucentVisBuffer = buffers.createDynamic();
        TerrainSectionListener.attach(this);
    }

    private static boolean anyWordSet(int[] shadow, int base) {
        for (int w = 0; w < GEOMETRY_MASK_WORDS; w++) {
            if (shadow[base + w] != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSlotLive(long pMeshData) {
        return sectionIndexCount(pMeshData) > 0 && SectionRenderDataUnsafe.getSliceMask(pMeshData) != 0;
    }

    private static int sectionIndexCount(long pMeshData) {
        long sumVertexCount = TerrainSectionMath.sumVertexCount(pMeshData);
        return (int) ((sumVertexCount >> 2) * 6L);
    }

    /**
     * Publish as the live {@link TerrainSectionListener#published()} handle the Sodium lifecycle hooks gate on;
     * idempotent.
     */
    public void publish() {
        TerrainSectionListener.publish(this);
    }

    public void unpublish() {
        TerrainSectionListener.unpublish(this);
    }

    public void setRegionFreedListener(IntConsumer listener) {
        this.regionFreedListener = listener;
    }

    public void onSectionMeshed(int regionId, int originX, int originY, int originZ, int localIndex,
                                long dataPtrSolid, long dataPtrCutout, long dataPtrTranslucent, int geometryHandle) {
        ensureRegionCapacity(regionId + 1);
        boolean wasAnyPresent = anySectionPresent(regionId, localIndex);
        copySlot(PASS_SOLID, regionId, localIndex, dataPtrSolid);
        copySlot(PASS_CUTOUT, regionId, localIndex, dataPtrCutout);
        copyTranslucentSlot(regionId, localIndex, dataPtrTranslucent);
        boolean nowAnyPresent = anySectionPresent(regionId, localIndex);
        if (nowAnyPresent && !wasAnyPresent) {
            seedFade(regionId, localIndex, originX, originY, originZ);
        } else if (wasAnyPresent && !nowAnyPresent) {
            resetTranslucentVis(regionId, localIndex);
            dropActiveFade(regionId, localIndex);
        }

        this.originChunkX[regionId] = originX;
        this.originChunkY[regionId] = originY;
        this.originChunkZ[regionId] = originZ;
        this.geometryHandle[regionId] = geometryHandle;
        this.live[regionId] = true;
    }

    private void copyTranslucentSlot(int regionId, int s, long srcPtr) {
        long dstOffset = ((long) regionId * REGION_SIZE + s) * SECTION_DATA_STRIDE;
        int idx = srcPtr == 0L ? 0 : sectionIndexCount(srcPtr);
        int prev = translucentSectionIndexCount[regionId * REGION_SIZE + s];
        if (idx != prev) {
            translucentSectionIndexCount[regionId * REGION_SIZE + s] = idx;
            translucentRegionIndexCountSum[regionId] += idx - prev;
        }
        if (idx <= 0) {
            clearTranslucentSlot(regionId, s);
            setTranslucentPresentBit(regionId, s, false);
            return;
        }
        translucentSectionDataMirror.write(dstOffset, srcPtr, SECTION_DATA_STRIDE);
        translucentDataDirty[(regionId * REGION_SIZE + s) >>> 6] |= 1L << (regionId * REGION_SIZE + s);
        setTranslucentPresentBit(regionId, s, true);
        if (idx > translucentRegionMaxIndexCount[regionId]) {
            translucentRegionMaxIndexCount[regionId] = idx;
        }
    }

    private boolean anySectionPresent(int regionId, int s) {
        return sectionPresentBit(PASS_SOLID, regionId, s) || sectionPresentBit(PASS_CUTOUT, regionId, s)
                || translucentPresentBit(regionId, s);
    }

    private boolean sectionPresentBit(int pass, int regionId, int s) {
        int word = regionId * GEOMETRY_MASK_WORDS + (s >>> 5);
        int[] shadow = presentMaskShadow[pass];
        return word < shadow.length && (shadow[word] & (1 << (s & 31))) != 0;
    }

    @Override
    public void markSection(RenderRegion region, int localIndex) {
        // pend() may grow pendingSections: index first.
        int base = pend(region);
        pendingSections[base + (localIndex >>> 6)] |= 1L << localIndex;
    }

    @Override
    public void markRegion(RenderRegion region) {
        int base = pend(region);
        Arrays.fill(pendingSections, base, base + PENDING_WORDS, -1L);
    }

    private int pend(RenderRegion region) {
        int regionId = region.getId();
        if (regionId >= pendingRegion.length) {
            int cap = Math.max(regionId + 1, pendingRegion.length * 2);
            pendingRegion = Arrays.copyOf(pendingRegion, cap);
            pendingSections = Arrays.copyOf(pendingSections, cap * PENDING_WORDS);
        }
        if (pendingRegion[regionId] == null) {
            pendingRegion[regionId] = region;
            pendingRegionIds.add(regionId);
        }
        return regionId * PENDING_WORDS;
    }

    private void feedPending() {
        for (int i = 0; i < pendingRegionIds.size(); i++) {
            int regionId = pendingRegionIds.getInt(i);
            RenderRegion region = pendingRegion[regionId];
            // Freed after marking (storages deleted).
            if (region == null) {
                continue;
            }
            pendingRegion[regionId] = null;
            int base = regionId * PENDING_WORDS;
            var resources = region.getResources();
            int handle = gpuBufferHandle(resources == null ? null : resources.getGeometryBuffer());
            int originX = region.getChunkX();
            int originY = region.getChunkY();
            int originZ = region.getChunkZ();
            if (handle != cachedGeometryHandle(regionId)) {
                noteRegionIdentity(regionId, originX, originY, originZ, handle);
                Arrays.fill(pendingSections, base, base + PENDING_WORDS, -1L);
            }
            var solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
            var cutout = region.getStorage(DefaultTerrainRenderPasses.CUTOUT);
            var translucent = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
            for (int w = 0; w < PENDING_WORDS; w++) {
                long bits = pendingSections[base + w];
                pendingSections[base + w] = 0L;
                for (; bits != 0L; bits &= bits - 1) {
                    int s = w * Long.SIZE + Long.numberOfTrailingZeros(bits);
                    onSectionMeshed(regionId, originX, originY, originZ, s,
                            solid == null ? 0L : solid.getDataPointer(s),
                            cutout == null ? 0L : cutout.getDataPointer(s),
                            translucent == null ? 0L : translucent.getDataPointer(s), handle);
                }
            }
        }
        pendingRegionIds.clear();
    }

    private static int gpuBufferHandle(@Nullable GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return -1;
        }
        if (VkContext.isVulkanHost()) {
            return buffer instanceof VulkanGpuBuffer vkBuffer ? (int) vkBuffer.vkBuffer() : -1;
        }
        return buffer instanceof GlBuffer glBuffer ? glBuffer.handle() : -1;
    }

    public void noteRegionIdentity(int regionId, int originX, int originY, int originZ, int geometryHandle) {
        ensureRegionCapacity(regionId + 1);
        this.originChunkX[regionId] = originX;
        this.originChunkY[regionId] = originY;
        this.originChunkZ[regionId] = originZ;
        this.geometryHandle[regionId] = geometryHandle;
    }

    /**
     * Read the cached geometry handle for a region (-1 if never seen). Drives the caller's realloc detection.
     */
    public int cachedGeometryHandle(int regionId) {
        return regionId < regionTableCap ? geometryHandle[regionId] : -1;
    }

    public void onSectionRemoved(int regionId, int localIndex) {
        if (regionId < 0 || regionId >= regionTableCap) {
            return;
        }
        boolean wasAnyPresent = anySectionPresent(regionId, localIndex);
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            clearSlot(pass, regionId, localIndex);
            setPresentBit(pass, regionId, localIndex, false);
        }
        clearTranslucentSlot(regionId, localIndex);
        setTranslucentPresentBit(regionId, localIndex, false);
        int prevIdx = translucentSectionIndexCount[regionId * REGION_SIZE + localIndex];
        if (prevIdx != 0) {
            translucentSectionIndexCount[regionId * REGION_SIZE + localIndex] = 0;
            translucentRegionIndexCountSum[regionId] -= prevIdx;
        }
        if (wasAnyPresent) {
            resetTranslucentVis(regionId, localIndex);
            dropActiveFade(regionId, localIndex);
        }
    }

    /**
     * Proactive id-recycle invalidation: clear ALL persistent state before the id can be reused for a different
     * region (Sodium's {@code IntPool} has no generation counter).
     */
    public void onRegionFreed(int regionId) {
        if (regionId < 0) {
            return;
        }
        // Not gated by regionTableCap: translucent-only regions hold fade timers without opaque table entries.
        if (regionFreedListener != null) {
            regionFreedListener.accept(regionId);
        }
        if (regionId < pendingRegion.length) {
            pendingRegion[regionId] = null;
            Arrays.fill(pendingSections, regionId * PENDING_WORDS, (regionId + 1) * PENDING_WORDS, 0L);
        }
        if (regionId >= regionTableCap) {
            return;
        }
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            clearPresentMaskRegion(pass, regionId);
            regionMaxIndexCount[pass][regionId] = 0;
        }
        clearTranslucentPresentMaskRegion(regionId);
        translucentRegionMaxIndexCount[regionId] = 0;
        translucentRegionIndexCountSum[regionId] = 0;
        Arrays.fill(translucentSectionIndexCount, regionId * REGION_SIZE, (regionId + 1) * REGION_SIZE, 0);
        clearTranslucentVisRegion(regionId);
        pruneActiveFadeRegion(regionId);
        live[regionId] = false;
        // Recycled id sharing the old buffer handle must still refeed all 256 sections.
        geometryHandle[regionId] = -1;
    }

    public boolean isLive(int regionId) {
        return regionId >= 0 && regionId < regionTableCap && live[regionId];
    }

    public boolean hasPresent(int pass, int regionId) {
        return anyWordSet(presentMaskShadow[pass], regionId * GEOMETRY_MASK_WORDS);
    }

    public int maxIndexCount(int pass, int regionId) {
        return regionMaxIndexCount[pass][regionId];
    }

    public int sectionVisHandle(int pass) {
        return sectionVisBuffers[pass].handle();
    }

    public long sectionVisAddress(int pass) {
        return sectionVisBuffers[pass].deviceAddress();
    }

    public long sectionVisByteSize() {
        return sectionVisBufferSize;
    }

    public int sectionDataHandle(int pass) {
        return sectionDataMirrors[pass].handle();
    }

    public long sectionDataAddress(int pass) {
        return sectionDataMirrors[pass].deviceAddress();
    }

    public long sectionDataByteCapacity(int pass) {
        return sectionDataMirrors[pass].byteCapacity();
    }

    public int presentMaskHandle(int pass) {
        return presentMaskBuffers[pass].handle();
    }

    public long presentMaskAddress(int pass) {
        return presentMaskBuffers[pass].deviceAddress();
    }

    public long presentMaskByteCapacity(int pass) {
        return (long) presentMaskRegionCap * GEOMETRY_MASK_WORDS * Integer.BYTES;
    }

    public int[] presentMaskShadow(int pass) {
        return presentMaskShadow[pass];
    }

    public boolean hasTranslucentPresent(int regionId) {
        if (regionId < 0 || regionId * GEOMETRY_MASK_WORDS + GEOMETRY_MASK_WORDS > translucentPresentMaskShadow.length) {
            return false;
        }
        return anyWordSet(translucentPresentMaskShadow, regionId * GEOMETRY_MASK_WORDS);
    }

    public int translucentMaxIndexCount(int regionId) {
        return translucentRegionMaxIndexCount[regionId];
    }

    public int translucentIndexCountSum(int regionId) {
        return translucentRegionIndexCountSum[regionId];
    }

    public int translucentSectionDataHandle() {
        return translucentSectionDataMirror.handle();
    }

    public long translucentSectionDataAddress() {
        return translucentSectionDataMirror.deviceAddress();
    }

    public long translucentSectionDataByteCapacity() {
        return translucentSectionDataMirror.byteCapacity();
    }

    public int translucentVisHandle() {
        return translucentVisBuffer.handle();
    }

    public long translucentVisAddress() {
        return translucentVisBuffer.deviceAddress();
    }

    public long translucentVisByteSize() {
        return translucentVisBufferSize;
    }

    /**
     * Whether any translucent section is mid-fade (skips the whole fading MDI stream otherwise).
     */
    public boolean hasActiveFades() {
        return !activeFadeFirstSeen.isEmpty();
    }

    public float sectionFadeVisibility(int regionId, int s, long now) {
        long key = ((long) regionId << 8) | (s & 0xFFL);
        if (!activeFadeFirstSeen.containsKey(key)) {
            return 1.0f;
        }
        long fadeMs = activeFadeDuration.get(key);
        return fadeMs <= 0L ? 1.0f : Mth.clamp((float) (now - activeFadeFirstSeen.get(key)) / (float) fadeMs, 0.0f,
                1.0f);
    }

    public void updateTranslucentFades(long now) {
        if (activeFadeFirstSeen.isEmpty()) {
            return;
        }
        LongIterator it = activeFadeFirstSeen.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            long firstSeen = activeFadeFirstSeen.get(key);
            long fadeMs = activeFadeDuration.get(key);
            int regionId = (int) (key >>> 8);
            int s = (int) (key & 0xFFL);
            float vis = fadeMs <= 0L ? 1.0f : Mth.clamp((float) (now - firstSeen) / (float) fadeMs, 0.0f, 1.0f);
            writeTranslucentVis(regionId, s, vis);
            if (vis >= 1.0f) {
                it.remove();
                activeFadeDuration.remove(key);
            }
        }
    }

    public long sectionDataVkBuffer(int pass) {
        return sectionDataMirrors[pass].vkBuffer();
    }

    public long sectionVisVkBuffer(int pass) {
        return sectionVisBuffers[pass].vkBuffer();
    }

    public long presentMaskVkBuffer(int pass) {
        return presentMaskBuffers[pass].vkBuffer();
    }

    public long translucentSectionDataVkBuffer() {
        return translucentSectionDataMirror.vkBuffer();
    }

    public long translucentVisVkBuffer() {
        return translucentVisBuffer.vkBuffer();
    }

    public void ensureSectionVisCapacity(int regionCap) {
        long needed = (long) regionCap * REGION_SIZE * VISIBILITY_STRIDE;
        if (needed > sectionVisBufferSize) {
            for (TerrainResidentBuffer sectionVisBuffer : sectionVisBuffers) {
                sectionVisBuffer.ensureCapacity(needed);
            }
            sectionVisBufferSize = needed;
        }
        long neededVis = (long) regionCap * REGION_SIZE * Float.BYTES;
        if (neededVis > translucentVisBufferSize) {
            translucentVisBuffer.ensureCapacity(neededVis);
            translucentVisBuffer.clearRange(0, neededVis, FLOAT_ONE_BITS);
            translucentVisBufferSize = neededVis;
        }
    }

    public void flushPendingUploads() {
        feedPending();
        buffers.flushPendingWrites();
    }

    // Unused section records are zero (Sodium callocs + clearFull) == a cleared slot.
    private static boolean isZeroRecord(long ptr) {
        for (long o = 0; o < SECTION_DATA_STRIDE; o += Long.BYTES) {
            if (MemoryUtil.memGetLong(ptr + o) != 0L) {
                return false;
            }
        }
        return true;
    }

    private void copySlot(int pass, int regionId, int s, long srcPtr) {
        long dstOffset = ((long) regionId * REGION_SIZE + s) * SECTION_DATA_STRIDE;
        if (srcPtr == 0L || isZeroRecord(srcPtr)) {
            clearSlot(pass, regionId, s);
            setPresentBit(pass, regionId, s, false);
            return;
        }
        sectionDataMirrors[pass].write(dstOffset, srcPtr, SECTION_DATA_STRIDE);
        sectionDataDirty[pass][(regionId * REGION_SIZE + s) >>> 6] |= 1L << (regionId * REGION_SIZE + s);

        boolean liveSlot = isSlotLive(srcPtr);
        setPresentBit(pass, regionId, s, liveSlot);
        if (liveSlot) {
            // Grow-only: shrinking re-mesh leaves it high => oversized shared index buffer only.
            int idx = sectionIndexCount(srcPtr);
            if (idx > regionMaxIndexCount[pass][regionId]) {
                regionMaxIndexCount[pass][regionId] = idx;
            }
        }
    }

    private void clearSlot(int pass, int regionId, int s) {
        int slot = regionId * REGION_SIZE + s;
        if (takeDirty(sectionDataDirty[pass], slot)) {
            sectionDataMirrors[pass].clearRange((long) slot * SECTION_DATA_STRIDE, SECTION_DATA_STRIDE, 0);
        }
    }

    // Past the mirror's capacity (region table can outgrow it): never written.
    private static boolean takeDirty(long[] dirty, int slot) {
        int w = slot >>> 6;
        if (w >= dirty.length) {
            return false;
        }
        long bit = 1L << slot;
        long word = dirty[w];
        dirty[w] = word & ~bit;
        return (word & bit) != 0;
    }

    private void setPresentBit(int pass, int regionId, int s, boolean set) {
        int word = regionId * GEOMETRY_MASK_WORDS + (s >>> 5);
        int[] shadow = presentMaskShadow[pass];
        int prev = shadow[word];
        int next = set ? (prev | (1 << (s & 31))) : (prev & ~(1 << (s & 31)));
        if (next == prev) {
            return;
        }
        shadow[word] = next;
        MemoryUtil.memPutInt(slotScratch.ptr(), next);
        presentMaskBuffers[pass].write((long) word * Integer.BYTES, slotScratch.ptr(), Integer.BYTES);
    }

    private void clearPresentMaskRegion(int pass, int regionId) {
        int base = regionId * GEOMETRY_MASK_WORDS;
        int[] shadow = presentMaskShadow[pass];
        boolean any = false;
        for (int w = 0; w < GEOMETRY_MASK_WORDS; w++) {
            if (shadow[base + w] != 0) {
                shadow[base + w] = 0;
                any = true;
            }
        }
        if (any) {
            presentMaskBuffers[pass].clearRange((long) base * Integer.BYTES, (long) GEOMETRY_MASK_WORDS * Integer.BYTES,
                    0);
        }
    }

    private void clearTranslucentSlot(int regionId, int s) {
        int slot = regionId * REGION_SIZE + s;
        if (takeDirty(translucentDataDirty, slot)) {
            translucentSectionDataMirror.clearRange((long) slot * SECTION_DATA_STRIDE, SECTION_DATA_STRIDE, 0);
        }
    }

    private boolean translucentPresentBit(int regionId, int s) {
        int word = regionId * GEOMETRY_MASK_WORDS + (s >>> 5);
        return word < translucentPresentMaskShadow.length
                && (translucentPresentMaskShadow[word] & (1 << (s & 31))) != 0;
    }

    private void setTranslucentPresentBit(int regionId, int s, boolean set) {
        int word = regionId * GEOMETRY_MASK_WORDS + (s >>> 5);
        if (word >= translucentPresentMaskShadow.length) {
            return;
        }
        int prev = translucentPresentMaskShadow[word];
        translucentPresentMaskShadow[word] = set ? (prev | (1 << (s & 31))) : (prev & ~(1 << (s & 31)));
    }

    private void clearTranslucentPresentMaskRegion(int regionId) {
        int base = regionId * GEOMETRY_MASK_WORDS;
        if (base + GEOMETRY_MASK_WORDS > translucentPresentMaskShadow.length) {
            return;
        }
        for (int w = 0; w < GEOMETRY_MASK_WORDS; w++) {
            translucentPresentMaskShadow[base + w] = 0;
        }
    }

    /**
     * Write one section's GPU-resident fade visibility (bounds-guarded: a not-yet-sized / non-visible region is skipped).
     */
    private void writeTranslucentVis(int regionId, int s, float vis) {
        long offset = ((long) regionId * REGION_SIZE + s) * Float.BYTES;
        if (offset + Float.BYTES <= translucentVisBufferSize) {
            MemoryUtil.memPutFloat(visScratch.ptr(), vis);
            translucentVisBuffer.write(offset, visScratch.ptr(), Float.BYTES);
        }
    }

    private void resetTranslucentVis(int regionId, int s) {
        writeTranslucentVis(regionId, s, 1.0f);
    }

    private void clearTranslucentVisRegion(int regionId) {
        long base = (long) regionId * REGION_SIZE * Float.BYTES;
        if (base + (long) REGION_SIZE * Float.BYTES <= translucentVisBufferSize) {
            translucentVisBuffer.clearRange(base, (long) REGION_SIZE * Float.BYTES, FLOAT_ONE_BITS);
        }
    }

    private void seedFade(int regionId, int s, int originX, int originY, int originZ) {
        long fadeMs = TerrainSectionMath.computeFadeDuration(
                (originX << 4) + TerrainSectionMath.localSectionX(s) * 16,
                (originY << 4) + TerrainSectionMath.localSectionY(s) * 16,
                (originZ << 4) + TerrainSectionMath.localSectionZ(s) * 16);
        if (fadeMs <= 0L) {
            return;
        }
        long key = ((long) regionId << 8) | (s & 0xFFL);
        activeFadeFirstSeen.put(key, Util.getMillis());
        activeFadeDuration.put(key, fadeMs);
    }

    private void dropActiveFade(int regionId, int s) {
        long key = ((long) regionId << 8) | (s & 0xFFL);
        activeFadeFirstSeen.remove(key);
        activeFadeDuration.remove(key);
    }

    private void pruneActiveFadeRegion(int regionId) {
        if (activeFadeFirstSeen.isEmpty()) {
            return;
        }
        long base = (long) regionId << 8;
        for (int s = 0; s < REGION_SIZE; s++) {
            long key = base | s;
            activeFadeFirstSeen.remove(key);
            activeFadeDuration.remove(key);
        }
    }

    private void ensureRegionCapacity(int regionCap) {
        ensureSectionDataCapacity(regionCap);
        ensurePresentMaskCapacity(regionCap);
        ensureRegionTableCapacity(regionCap);
    }

    private void ensureSectionDataCapacity(int regionCap) {
        if (regionCap > METADATA_REGION_ID_CAP) {
            throw new IllegalStateException("Terrain metadata region-id space exceeded cap: " + regionCap
                    + " > " + METADATA_REGION_ID_CAP);
        }
        long needed = (long) regionCap * REGION_SIZE * SECTION_DATA_STRIDE;
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            if (needed > sectionDataMirrorBytes[pass]) {
                int newCap = regionCap <= 1 ? 1
                        : Math.min(METADATA_REGION_ID_CAP, Integer.highestOneBit(regionCap - 1) << 1);
                long newBytes = (long) newCap * REGION_SIZE * SECTION_DATA_STRIDE;
                // Pending mirror writes land before the realloc's old -> new copy.
                if (sectionDataMirrors[pass].byteCapacity() > 0) {
                    buffers.flushBeforeGrow();
                }
                sectionDataMirrors[pass].ensureCapacity(newBytes);
                sectionDataMirrorBytes[pass] = newBytes;
                sectionDataDirty[pass] = growDirty(sectionDataDirty[pass], newCap);
            }
        }
        if (needed > translucentSectionDataMirrorBytes) {
            int newCap = regionCap <= 1 ? 1
                    : Math.min(METADATA_REGION_ID_CAP, Integer.highestOneBit(regionCap - 1) << 1);
            long newBytes = (long) newCap * REGION_SIZE * SECTION_DATA_STRIDE;
            if (translucentSectionDataMirror.byteCapacity() > 0) {
                buffers.flushBeforeGrow();
            }
            translucentSectionDataMirror.ensureCapacity(newBytes);
            translucentSectionDataMirrorBytes = newBytes;
            translucentDataDirty = growDirty(translucentDataDirty, newCap);
        }
    }

    // Grown storage content is undefined => new slots start dirty.
    private static long[] growDirty(long[] dirty, int regionCap) {
        int oldLength = dirty.length;
        long[] grown = Arrays.copyOf(dirty, regionCap * REGION_SIZE / Long.SIZE);
        Arrays.fill(grown, oldLength, grown.length, -1L);
        return grown;
    }

    private void ensurePresentMaskCapacity(int regionCap) {
        if (regionCap <= presentMaskRegionCap) {
            return;
        }
        int newCap = Math.min(METADATA_REGION_ID_CAP, Math.max(regionCap, presentMaskRegionCap * 2));
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            int[] grown = new int[newCap * GEOMETRY_MASK_WORDS];
            System.arraycopy(presentMaskShadow[pass], 0, grown, 0, presentMaskShadow[pass].length);
            presentMaskShadow[pass] = grown;
            // Resident grow recreates storage without content => re-upload whole mask.
            uploadPresentMask(pass, newCap);
        }
        int[] grownT = new int[newCap * GEOMETRY_MASK_WORDS];
        System.arraycopy(translucentPresentMaskShadow, 0, grownT, 0, translucentPresentMaskShadow.length);
        translucentPresentMaskShadow = grownT;
        presentMaskRegionCap = newCap;
    }

    private void uploadPresentMask(int pass, int regionCap) {
        long bytes = (long) regionCap * GEOMETRY_MASK_WORDS * Integer.BYTES;
        MemoryBlock block = MemoryBlock.malloc(bytes);
        int[] shadow = presentMaskShadow[pass];
        long ptr = block.ptr();
        int words = regionCap * GEOMETRY_MASK_WORDS;
        for (int i = 0; i < words; i++) {
            MemoryUtil.memPutInt(ptr + (long) i * Integer.BYTES, shadow[i]);
        }
        presentMaskBuffers[pass].ensureCapacity(bytes);
        presentMaskBuffers[pass].write(0, block.ptr(), bytes);
        block.free();
    }

    private void ensureRegionTableCapacity(int regionCap) {
        if (regionCap <= regionTableCap) {
            return;
        }
        int newCap = Math.min(METADATA_REGION_ID_CAP, Math.max(regionCap, regionTableCap * 2));
        originChunkX = Arrays.copyOf(originChunkX, newCap);
        originChunkY = Arrays.copyOf(originChunkY, newCap);
        originChunkZ = Arrays.copyOf(originChunkZ, newCap);
        live = Arrays.copyOf(live, newCap);
        geometryHandle = Arrays.copyOf(geometryHandle, newCap);
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            regionMaxIndexCount[pass] = Arrays.copyOf(regionMaxIndexCount[pass], newCap);
        }
        translucentRegionMaxIndexCount = Arrays.copyOf(translucentRegionMaxIndexCount, newCap);
        translucentRegionIndexCountSum = Arrays.copyOf(translucentRegionIndexCountSum, newCap);
        translucentSectionIndexCount = Arrays.copyOf(translucentSectionIndexCount, newCap * REGION_SIZE);
        regionTableCap = newCap;
    }

    public void delete() {
        TerrainSectionListener.unpublish(this);
        TerrainSectionListener.detach(this);
        for (TerrainResidentBuffer mirror : sectionDataMirrors) {
            mirror.delete();
        }
        for (TerrainResidentBuffer b : presentMaskBuffers) {
            b.delete();
        }
        for (TerrainResidentBuffer b : sectionVisBuffers) {
            b.delete();
        }
        translucentSectionDataMirror.delete();
        translucentVisBuffer.delete();
        slotScratch.free();
        visScratch.free();
    }
}
