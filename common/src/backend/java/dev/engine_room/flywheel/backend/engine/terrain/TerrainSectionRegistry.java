package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
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
    private int[] regionMaxVertexExtent = new int[0];
    @Nullable
    private IntConsumer regionFreedListener;

    @Nullable
    private OwnedGeometryListener ownedGeometryListener;

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

    private static int sectionVertexExtent(long pMeshData) {
        long sumVertexCount = TerrainSectionMath.sumVertexCount(pMeshData);
        return (int) (SectionRenderDataUnsafe.getBaseVertex(pMeshData) + sumVertexCount);
    }

    // ============================================================================================
    //  Hook 1 -- RenderRegionManager.uploadResults(region, results, uniforms) @RETURN

    static TerrainRenderPass passFor(int pass) {
        return pass == PASS_SOLID ? DefaultTerrainRenderPasses.SOLID : DefaultTerrainRenderPasses.CUTOUT;
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

    public void setOwnedGeometryListener(@Nullable OwnedGeometryListener listener) {
        this.ownedGeometryListener = listener;
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

        int extent = 0;
        if (dataPtrSolid != 0L) {
            extent = Math.max(extent, sectionVertexExtent(dataPtrSolid));
        }
        if (dataPtrCutout != 0L) {
            extent = Math.max(extent, sectionVertexExtent(dataPtrCutout));
        }
        if (dataPtrTranslucent != 0L) {
            extent = Math.max(extent, sectionVertexExtent(dataPtrTranslucent));
        }
        if (extent > regionMaxVertexExtent[regionId]) {
            regionMaxVertexExtent[regionId] = extent;
        }

        // Notify the owned-geometry tier that this region's arena content changed (a section re-meshed), so it
        // re-gathers Sodium's live arena into its owned copy; in-place updates keep the same GL/VK handle.
        if (ownedGeometryListener != null) {
            ownedGeometryListener.onRegionDirty(regionId);
        }
    }

    // ============================================================================================
    //  Hook 2 -- RenderRegion.removeSection(section) @HEAD

    private void copyTranslucentSlot(int regionId, int s, long srcPtr) {
        long dstOffset = ((long) regionId * REGION_SIZE + s) * SECTION_DATA_STRIDE;
        int idx = srcPtr == 0L ? 0 : sectionIndexCount(srcPtr);
        int prev = translucentSectionIndexCount[regionId * REGION_SIZE + s];
        if (idx != prev) {
            translucentSectionIndexCount[regionId * REGION_SIZE + s] = idx;
            translucentRegionIndexCountSum[regionId] += idx - prev;
        }
        if (idx <= 0) {
            clearTranslucentSlotAt(dstOffset);
            setTranslucentPresentBit(regionId, s, false);
            return;
        }
        translucentSectionDataMirror.write(dstOffset, srcPtr, SECTION_DATA_STRIDE);
        setTranslucentPresentBit(regionId, s, true);
        if (idx > translucentRegionMaxIndexCount[regionId]) {
            translucentRegionMaxIndexCount[regionId] = idx;
        }
    }

    // ============================================================================================
    //  Hook 3 -- RenderRegionManager.update() at freeIds.release(region.getId())

    private boolean anySectionPresent(int regionId, int s) {
        return sectionPresentBit(PASS_SOLID, regionId, s) || sectionPresentBit(PASS_CUTOUT, regionId, s)
                || translucentPresentBit(regionId, s);
    }

    private boolean sectionPresentBit(int pass, int regionId, int s) {
        int word = regionId * GEOMETRY_MASK_WORDS + (s >>> 5);
        int[] shadow = presentMaskShadow[pass];
        return word < shadow.length && (shadow[word] & (1 << (s & 31))) != 0;
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
        clearSectionVis(regionId, localIndex);
        clearTranslucentSlotAt(((long) regionId * REGION_SIZE + localIndex) * SECTION_DATA_STRIDE);
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
        // Prune CPU-side per-region state (translucent fade timers) FIRST, unconditionally for any valid id: a
        // translucent-only region can hold fade entries without ever populating the opaque resident table, so this
        // must not be gated by regionTableCap. Closes the IntPool id-recycle fade-timestamp collision + the leak.
        if (regionFreedListener != null) {
            regionFreedListener.accept(regionId);
        }
        // Drop the owned-geometry copy for the recycled id (unconditional for any valid id, like the fade prune: a
        // region can own geometry without ever populating the opaque table, so this must precede the cap gate).
        if (ownedGeometryListener != null) {
            ownedGeometryListener.onRegionFreed(regionId);
        }
        if (regionId >= regionTableCap) {
            return;
        }
        long base = (long) regionId * REGION_SIZE * VISIBILITY_STRIDE;
        if (base + (long) REGION_SIZE * VISIBILITY_STRIDE <= sectionVisBufferSize) {
            for (TerrainResidentBuffer sectionVisBuffer : sectionVisBuffers) {
                sectionVisBuffer.clearRange(base, (long) REGION_SIZE * VISIBILITY_STRIDE, 0);
            }
        }
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            clearPresentMaskRegion(pass, regionId);
            regionMaxIndexCount[pass][regionId] = 0;
        }
        regionMaxVertexExtent[regionId] = 0;
        clearTranslucentPresentMaskRegion(regionId);
        translucentRegionMaxIndexCount[regionId] = 0;
        translucentRegionIndexCountSum[regionId] = 0;
        Arrays.fill(translucentSectionIndexCount, regionId * REGION_SIZE, (regionId + 1) * REGION_SIZE, 0);
        clearTranslucentVisRegion(regionId);
        pruneActiveFadeRegion(regionId);
        live[regionId] = false;
        // Reset the cached geometry handle so a recycled region-id always observes a handle CHANGE on its next
        // mesh-applied hook, forcing the full-region recopy (Hook 1's handleChanged path). Without this, if the
        // recycled region happens to reuse the same GL handle, handleChanged would falsely report "unchanged" and
        // skip the recopy, relying implicitly on the present-mask clear above.
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

    public int regionMaxVertexExtent(int regionId) {
        return regionId >= 0 && regionId < regionTableCap ? regionMaxVertexExtent[regionId] : 0;
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
                sectionVisBuffer.clearRange(0, needed, 0);
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
        buffers.flushPendingWrites();
    }

    public void clearSectionVisRegion(int pass, int regionId) {
        long base = (long) regionId * REGION_SIZE * VISIBILITY_STRIDE;
        if (base + (long) REGION_SIZE * VISIBILITY_STRIDE <= sectionVisBufferSize) {
            sectionVisBuffers[pass].clearRange(base, (long) REGION_SIZE * VISIBILITY_STRIDE, 0);
        }
    }

    private void copySlot(int pass, int regionId, int s, long srcPtr) {
        long dstOffset = ((long) regionId * REGION_SIZE + s) * SECTION_DATA_STRIDE;
        if (srcPtr == 0L) {
            clearSlotAt(pass, dstOffset);
            setPresentBit(pass, regionId, s, false);
            return;
        }
        sectionDataMirrors[pass].write(dstOffset, srcPtr, SECTION_DATA_STRIDE);

        boolean liveSlot = isSlotLive(srcPtr);
        setPresentBit(pass, regionId, s, liveSlot);
        if (liveSlot) {
            // Monotonic over-estimate of the per-region max index count: it only grows. A re-mesh that shrinks the
            // largest section leaves the estimate high, which over-sizes the shared index buffer (harmless -- the GPU
            int idx = sectionIndexCount(srcPtr);
            if (idx > regionMaxIndexCount[pass][regionId]) {
                regionMaxIndexCount[pass][regionId] = idx;
            }
        }
    }

    private void clearSlot(int pass, int regionId, int s) {
        clearSlotAt(pass, ((long) regionId * REGION_SIZE + s) * SECTION_DATA_STRIDE);
    }

    private void clearSlotAt(int pass, long dstOffset) {
        if (dstOffset + SECTION_DATA_STRIDE <= sectionDataMirrors[pass].byteCapacity()) {
            sectionDataMirrors[pass].clearRange(dstOffset, SECTION_DATA_STRIDE, 0);
        }
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

    private void clearSectionVis(int regionId, int s) {
        long offset = ((long) regionId * REGION_SIZE + s) * VISIBILITY_STRIDE;
        if (offset + VISIBILITY_STRIDE <= sectionVisBufferSize) {
            for (TerrainResidentBuffer sectionVisBuffer : sectionVisBuffers) {
                sectionVisBuffer.clearRange(offset, VISIBILITY_STRIDE, 0);
            }
        }
    }

    private void clearTranslucentSlotAt(long dstOffset) {
        if (dstOffset + SECTION_DATA_STRIDE <= translucentSectionDataMirror.byteCapacity()) {
            translucentSectionDataMirror.clearRange(dstOffset, SECTION_DATA_STRIDE, 0);
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
                // Flush deferred mirror writes (+ barrier on GL) before a realloc's old->new content copy, so pending
                if (sectionDataMirrors[pass].byteCapacity() > 0) {
                    buffers.flushBeforeGrow();
                }
                sectionDataMirrors[pass].ensureCapacity(newBytes);
                sectionDataMirrorBytes[pass] = newBytes;
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
        }
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
            // Re-upload the whole mask: a resident grow recreates the storage (new device address, no content
            uploadPresentMask(pass, newCap);
        }
        // Translucent present shadow is CPU-only (no GL buffer): just grow the array.
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
        regionMaxVertexExtent = Arrays.copyOf(regionMaxVertexExtent, newCap);
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
