package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.*;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.buffer.GlResidentBuffer;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.caffeinemc.mods.sodium.client.util.iterator.ReversibleObjectArrayIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ObjIntConsumer;

public final class TerrainDrawDispatcher implements TerrainDispatcher {
    static final int MAX_COMMANDS_PER_REGION = ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE;
    static final int COMMAND_STRIDE = 20;
    static final int MAX_TEMPORAL_COMMANDS_PER_REGION = MAX_COMMANDS_PER_REGION;
    static final int FACING_COUNT = ModelQuadFacing.COUNT;
    static final int REGION_SIZE = RenderRegion.REGION_SIZE;
    static final int GEOMETRY_MASK_WORDS = REGION_SIZE / Integer.SIZE;
    static final int VISIBILITY_STRIDE = Integer.BYTES;
    static final int PASS_SOLID = 0;
    private static final int REGION_INPUT_STRIDE = 16;
    private static final int MAX_VISIBLE_REGIONS = 4096;
    private static final int PASS_CUTOUT = 1;
    private static final int PASS_COUNT = 2;
    private static final int TRANSLUCENT_SECTION_INITIAL_CAP = 4096;
    private static final int[] CLEAR_R32UI = {0};
    private static final int[] CLEAR_RG32UI = {0, 0, 0, 0};

    // ---- SSBO binding points (terrain HiZ programs own 0-6; sampler 10; UBO 8) ----------------------------
    private static final int BINDING_REGION_INPUT = 0;
    private static final int BINDING_SECTION_DATA = 1;
    private static final int BINDING_REGION_VIS = 2;
    private static final int BINDING_SECTION_VIS = 3;
    private static final int BINDING_COMMAND_BUFFER = 4;
    private static final int BINDING_REGION_COMMAND_COUNT = 5;
    private static final int BINDING_GEOMETRY_MASK = 6;
    private static final int BINDING_TERRAIN_SCENE_UBO = 7;
    private static final int BINDING_TERRAIN_HIZ_UBO = 8;
    private static final long TERRAIN_SCENE_UBO_BYTES = 64L;
    private static final int MAX_TRANSLUCENT_COMMANDS_PER_STREAM = REGION_SIZE;
    public static final long TRANSLUCENT_REGION_COMMAND_BYTES =
            2L * MAX_TRANSLUCENT_COMMANDS_PER_STREAM * COMMAND_STRIDE;
    public static final long TRANSLUCENT_FADING_BYTE_OFFSET = (long) MAX_TRANSLUCENT_COMMANDS_PER_STREAM * COMMAND_STRIDE;
    private static final int BINDING_SECTION_FADE_VIS = 11;
    /**
     * Per-region {@code u_RegionChunkOrigin} UBO binding (UBO namespace, distinct from the SSBO binds above).
     */
    private static final int BINDING_REGION_CHUNK_ORIGIN = 10;
    private static final int TERRAIN_VERTEX_STRIDE = CompactChunkVertex.VERTEX_FORMAT.getVertexSize();
    private static final int TRANSLUCENT_COMMAND_REGION_CAP_INIT = 256;
    private static final int LIVE_MASK_WORDS_PER_SLOT = REGION_SIZE / Integer.SIZE;
    private static final int COMMAND_REGION_CAP = 256;
    /**
     * Optional mesh-shader opaque draw strategy; {@code null} = the MDI path.
     */
    @Nullable
    private static ObjIntConsumer<TerrainDrawDispatcher> meshDrawStrategy;
    @Nullable
    private static TerrainTranslucentMeshDrawStrategy translucentMeshDrawStrategy;
    private static boolean terrainUnsupportedLogged = false;
    private static boolean terrainInitFailed = false;
    // The stashed mesh-tier pyramid snapshot of THIS frame; consumed once at the engine's post-draw point
    @Nullable
    private static Runnable deferredMeshRegen;
    private static boolean regionCapWarned = false;
    public final TerrainSectionRegistry registry;
    public final GlResidentBuffer[] regionInputBuffers = {
            new GlResidentBuffer(),
            new GlResidentBuffer(),
    };
    public final GlResidentBuffer regionVisBuffer = new GlResidentBuffer();
    public final GlResidentBuffer[][] commandBuffers = {
            {new GlResidentBuffer(), new GlResidentBuffer()},
            {new GlResidentBuffer(), new GlResidentBuffer()}
    };

    public final GlResidentBuffer[][] regionCommandCounts = {
            {new GlResidentBuffer(), new GlResidentBuffer()},
            {new GlResidentBuffer(), new GlResidentBuffer()}
    };
    /**
     * Translucent region-input (regionId + origin per region); resident so the cull scene UBO can address it.
     */
    public final GlResidentBuffer translucentRegionInputBuffer = new GlResidentBuffer();
    public final GlResidentBuffer translucentRegionVisBuffer = new GlResidentBuffer();
    public final GlResidentBuffer translucentCommandBuffer = new GlResidentBuffer();
    public final GlResidentBuffer translucentCommandCount = new GlResidentBuffer();
    public final GlBuffer translucentHizUniformBuffer = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    public final GlBuffer translucentLiveMaskBuffer = new GlBuffer(GlBufferUsage.STREAM_DRAW);
    public final TranslucentRegionBatch translucentRegionBatch = new TranslucentRegionBatch();
    private final SharedQuadIndexBuffer sharedIndexBuffer = new SharedQuadIndexBuffer(
            SharedQuadIndexBuffer.IndexFormat.INTEGER);
    /**
     * Terrain-owned staging buffer (the instance path's isn't flushed at the cancel position).
     */
    private final StagingBuffer stagingBuffer;
    /**
     * Terrain-owned HiZ pyramid (separate from the instance-occlusion pyramid to avoid racy contention).
     */
    private final DepthPyramid terrainDepthPyramid;
    private final GlProgram regionTestProgram;
    private final GlProgram sectionTestProgram;
    private final GlProgram commandBuilderProgram;
    private final GlProgram translucentCullBuildProgram;
    private final DepthPyramid translucentDepthPyramid;
    private final MemoryBlock translucentHizUniformScratch = MemoryBlock.malloc(96L);
    private final MemoryBlock translucentRegionInputScratch =
            MemoryBlock.malloc((long) MAX_VISIBLE_REGIONS * REGION_INPUT_STRIDE);
    private final MemoryBlock translucentLiveMaskScratch =
            MemoryBlock.malloc((long) MAX_VISIBLE_REGIONS * LIVE_MASK_WORDS_PER_SLOT * Integer.BYTES);
    private final Matrix4f lastProjection = new Matrix4f();
    private final MemoryBlock regionInputScratch = MemoryBlock.malloc((long) MAX_VISIBLE_REGIONS * REGION_INPUT_STRIDE);
    private final DynamicUniformStorage<RegionChunkOriginUniform> regionOriginUniforms =
            new DynamicUniformStorage<>("flywheel:terrain_region_origin", 16, 256);
    private final DynamicUniformStorage<ChunkSectionUniform> chunkSectionUniforms =
            new DynamicUniformStorage<>("flywheel:terrain_chunk_section", 96, 4);
    private final DynamicUniformStorage<ChunkSectionUniform> translucentChunkSectionUniforms =
            new DynamicUniformStorage<>("flywheel:terrain_translucent_chunk_section", 96, 64);
    private final VisibleRegionBatch solidBatch = new VisibleRegionBatch(PASS_SOLID);
    private final VisibleRegionBatch cutoutBatch = new VisibleRegionBatch(PASS_CUTOUT);
    private final OpaqueTerrainBatch opaqueBatch = new OpaqueTerrainBatch(solidBatch, cutoutBatch);
    private final TranslucentBatch translucentBatch = new TranslucentBatch();
    private final Long2LongMap translucentFirstSeenMillis = new Long2LongOpenHashMap();
    private final Long2LongMap translucentFadeDurationMillis = new Long2LongOpenHashMap();
    private final GlBuffer hizUniformBuffer =
            new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    private final MemoryBlock hizUniformScratch = MemoryBlock.malloc(96L);
    private final TerrainGpuBuilderValidator gpuBuilderValidator;
    private final GlBuffer terrainSceneUbo = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    private final MemoryBlock terrainSceneUboScratch = MemoryBlock.malloc(TERRAIN_SCENE_UBO_BYTES);
    private final GpuSampler atlasSampler;
    private final GpuSampler lightmapSampler;
    private final IntSet[] lastFrameVisibleRegions = {
            new IntOpenHashSet(),
            new IntOpenHashSet()
    };
    private final IntOpenHashSet frustumLeaveScratch = new IntOpenHashSet();
    // Pass-A previous-slot replay state. The replay draws LAST FRAME's command buffer into depth; it is valid only
    // when this frame's slot maps to the SAME region identity (regionId + origin + geometry/index GL handle) as last
    private final Int2IntMap[] previousSlotByRegionId = newIntMaps();
    private final int[][] previousRegionIds = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[][] previousOriginChunkX = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[][] previousOriginChunkY = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[][] previousOriginChunkZ = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[][] previousGeometryHandles = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[][] previousIndexHandles = new int[PASS_COUNT][MAX_VISIBLE_REGIONS];
    private final int[] previousVisibleCounts = new int[PASS_COUNT];
    private final Matrix4f lastModelView = new Matrix4f();
    private final Matrix4f selfEnumViewProj = new Matrix4f();
    private final FrustumIntersection selfEnumFrustum = new FrustumIntersection();
    private final SodiumTerrainOitReplay oitReplay = new SodiumTerrainOitReplay() {
        @Override
        public void prepareCull(GpuTextureView depthView, int width, int height) {
            if (translucentGpuDriven) {
                prepareTranslucentCull(depthView.texture(), width, height);
            }
        }

        @Override
        public void replay(RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                           GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                           GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler) {
            if (translucentGpuDriven) {
                replayTranslucentOitGpu(pass, mode, framebuffer, lightmapView, blueNoiseView,
                        clampLinear, oitSampler, noiseSampler);
            } else {
                replayTranslucentOit(pass, mode, framebuffer, lightmapView, blueNoiseView,
                        clampLinear, oitSampler, noiseSampler);
            }
        }

        @Override
        public boolean supportsInsert() {
            // Both the GPU-driven MDI stream and the mesh-shader strategy have an insert (mlab) twin.
            return translucentGpuDriven;
        }

        @Override
        public void replayInsert(RenderPass pass, OitInsertMode mode, GpuTextureView lightmapView,
                                 GpuSampler clampLinear) {
            replayTranslucentOitInsert(pass, mode, lightmapView, clampLinear);
        }
    };
    @Nullable
    public VisibleRegionBatch boundBatch;
    public int boundBufferIndex;
    private int currBufferIndex = 0;
    private int translucentCommandRegionCap = 0;
    private boolean translucentGpuDriven = false;
    private boolean captureTranslucent = true;
    private boolean terrainSceneUboAllocated = false;
    private boolean firstFrame = true;
    private boolean passADepthAvailable = false;
    private boolean meshHizActive = false;
    private boolean meshDepthPyramidValid = false;
    private int meshPyramidWidth = -1;
    private int meshPyramidHeight = -1;
    private boolean lastModelViewValid = false;
    private int commandRegionCap = COMMAND_REGION_CAP;

    public TerrainDrawDispatcher() {
        atlasSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
        lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        IndirectPrograms programs = IndirectPrograms.get();
        try {
            stagingBuffer = new StagingBuffer(programs);
            terrainDepthPyramid = new DepthPyramid(programs);
            translucentDepthPyramid = new DepthPyramid(programs);
            regionTestProgram = programs.getTerrainRegionTestProgram();
            sectionTestProgram = programs.getTerrainSectionTestProgram();
            commandBuilderProgram = programs.getTerrainCommandBuilderProgram();
            translucentCullBuildProgram = programs.getTerrainTranslucentCullBuildProgram();

            registry = new TerrainSectionRegistry(new GlTerrainResidentBuffers(stagingBuffer));
            registry.ensureSectionVisCapacity(MAX_VISIBLE_REGIONS);
            registry.setRegionFreedListener(this::pruneTranslucentFade);
            gpuBuilderValidator = new TerrainGpuBuilderValidator(regionVisBuffer, commandBuffers, regionCommandCounts,
                    registry);

            zeroFillResident(regionVisBuffer, (long) MAX_VISIBLE_REGIONS * VISIBILITY_STRIDE);
            zeroFillResident(translucentRegionVisBuffer, (long) MAX_VISIBLE_REGIONS * VISIBILITY_STRIDE);

            for (GlResidentBuffer b : regionInputBuffers) {
                zeroFillResident(b, (long) MAX_VISIBLE_REGIONS * REGION_INPUT_STRIDE);
            }
            zeroFillResident(translucentRegionInputBuffer, (long) MAX_VISIBLE_REGIONS * REGION_INPUT_STRIDE);

            long commandBytesPerRegion = ((long) MAX_COMMANDS_PER_REGION + MAX_TEMPORAL_COMMANDS_PER_REGION) * COMMAND_STRIDE;
            for (int pass = 0; pass < PASS_COUNT; pass++) {
                for (GlResidentBuffer b : commandBuffers[pass]) {
                    zeroFillResident(b, commandBytesPerRegion * COMMAND_REGION_CAP);
                }
                for (GlResidentBuffer b : regionCommandCounts[pass]) {
                    zeroFillResident(b, (long) COMMAND_REGION_CAP * 8L);
                }
            }
        } catch (Throwable t) {
            freeAll();
            throw t;
        }
    }

    public static void setMeshDrawStrategy(@Nullable ObjIntConsumer<TerrainDrawDispatcher> strategy) {
        meshDrawStrategy = strategy;
    }

    /**
     * Register (or clear, with {@code null}) the mesh-shader translucent-terrain draw strategy; called once by the
     * mesh tier at init.
     */
    public static void setTranslucentMeshDrawStrategy(@Nullable TerrainTranslucentMeshDrawStrategy strategy) {
        translucentMeshDrawStrategy = strategy;
    }

    public static boolean isSupported() {
        return GlCompat.SUPPORTS_TERRAIN && IndirectPrograms.allLoaded() && !terrainInitFailed;
    }

    public static void logUnsupportedOnce() {
        if (terrainUnsupportedLogged) {
            return;
        }
        terrainUnsupportedLogged = true;
        String reason = GlCompat.TERRAIN_UNSUPPORTED_REASON;
        if (reason == null && !IndirectPrograms.allLoaded()) {
            reason = "indirect compute programs did not load";
        } else if (reason == null && terrainInitFailed) {
            reason = "terrain shader/program initialization failed";
        }
        FlwBackend.LOGGER.warn("Flywheel terrain T2 disabled; Sodium terrain will render normally ({})", reason);
    }

    public static void disableAfterInitFailure(RuntimeException e) {
        terrainInitFailed = true;
        if (!terrainUnsupportedLogged) {
            terrainUnsupportedLogged = true;
            FlwBackend.LOGGER.warn(
                    "Flywheel terrain T2 disabled after shader/program initialization failure; Sodium terrain will render normally",
                    e);
        }
    }

    private static Int2IntMap[] newIntMaps() {
        Int2IntMap[] maps = new Int2IntMap[PASS_COUNT];
        for (int i = 0; i < PASS_COUNT; i++) {
            Int2IntOpenHashMap map = new Int2IntOpenHashMap();
            map.defaultReturnValue(-1);
            maps[i] = map;
        }
        return maps;
    }

    private static void zeroFillResident(GlResidentBuffer buffer, long bytes) {
        buffer.ensureCapacity(bytes);
        MemoryBlock block = MemoryBlock.calloc(bytes, 1);
        buffer.uploadSpan(0, block);
        block.free();
    }

    public static void runDeferredMeshRegen() {
        Runnable r = deferredMeshRegen;
        deferredMeshRegen = null;
        if (r != null) {
            r.run();
        }
    }

    private static int gpuBufferHandle(GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return -1;
        }
        if (!(buffer instanceof com.mojang.blaze3d.opengl.GlBuffer glBuffer)) {
            return -1;
        }
        return glBuffer.handle();
    }

    private static void warnRegionCapOnce() {
        if (!regionCapWarned) {
            regionCapWarned = true;
            FlwBackend.LOGGER.warn(
                    "[terrain] MAX_VISIBLE_REGIONS ({}) hit under single-cull self-enumeration -- regions"
                            + " dropped; the A/B at this render distance may undercount. Lower render distance or grow the cap.",
                    MAX_VISIBLE_REGIONS);
        }
    }

    /**
     * Single-cull animated-sprite tick: no render list exists, so scan all 256 sections of each frustum-passed region.
     */
    private static void markAnimatedSpritesResident(RenderRegion region) {
        for (int s = 0; s < 256; s++) {
            TextureAtlasSprite[] sprites = region.getAnimatedSprites(s);
            if (sprites == null) {
                continue;
            }
            for (TextureAtlasSprite sprite : sprites) {
                SpriteUtil.INSTANCE.markSpriteActive(sprite);
            }
        }
    }

    private static void markAnimatedSpritesActive(RenderRegion region, ChunkRenderList renderList) {
        var sectionIt = renderList.sectionsWithSpritesIterator();
        if (sectionIt == null) {
            return;
        }
        while (sectionIt.hasNext()) {
            TextureAtlasSprite[] sprites = region.getAnimatedSprites(sectionIt.nextByteAsInt());
            if (sprites == null) {
                continue;
            }
            for (TextureAtlasSprite sprite : sprites) {
                SpriteUtil.INSTANCE.markSpriteActive(sprite);
            }
        }
    }

    private boolean ensureCommandCapacity(int visibleCount, long cmdBytesPerRegion) {
        if (visibleCount <= commandRegionCap) {
            return false;
        }
        int newCap = Math.max(visibleCount, (int) (commandRegionCap * 2));
        long totalCmdBytes = cmdBytesPerRegion * newCap;
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            for (GlResidentBuffer b : commandBuffers[pass]) {
                zeroFillResident(b, totalCmdBytes);
            }
            for (GlResidentBuffer b : regionCommandCounts[pass]) {
                zeroFillResident(b, (long) newCap * 8L);
            }
        }
        commandRegionCap = newCap;
        return true;
    }

    /**
     * Rotate the per-frame UBO rings; driven from the translucent seam tail, not beginFrame: the OIT replay reads
     * the rings at the seam, so they must stay valid until after it.
     */
    public void endFrame() {
        regionOriginUniforms.endFrame();
        chunkSectionUniforms.endFrame();
        translucentChunkSectionUniforms.endFrame();
        lastModelViewValid = false;
        // Clear the GPU region batch's geometry/origin-slice refs so a frame with no opaque seam can't replay stale
        translucentRegionBatch.reset();
    }

    public void publishRegistry() {
        registry.publish();
    }

    public void unpublishRegistry() {
        registry.unpublish();
    }

    @Override
    public boolean drawOpaqueSolid(ChunkRenderMatrices matrices, RenderSectionManager manager,
                                   @Nullable Collection<RenderRegion> selfEnum) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            // Transient: no render target this frame -> drew nothing, leave Sodium to draw opaque (don't cancel).
            return false;
        }

        lastModelView.set(matrices.modelView());
        lastProjection.set(matrices.projection());
        lastModelViewValid = true;
        translucentGpuDriven = true;
        // terrainMode OPAQUE: skip the translucent half of the walk -- the batch stays empty, translucentOitReplay()
        captureTranslucent = BackendConfig.INSTANCE.terrainMode().compositesTranslucent();

        // Apply the staging copies the hooks enqueued during extract. flush() dispatches the scatter compute, so it
        // MUST run here in the draw window (one flush/frame), not in the extract-phase hooks.
        registry.flushPendingUploads();

        OpaqueTerrainBatch visible = collectVisibleRegions(manager, selfEnum);
        int maxRegionId = Math.max(visible.maxRegionId, translucentRegionBatch.maxRegionId);
        if (maxRegionId >= 0) {
            registry.ensureSectionVisCapacity(maxRegionId + 1);
        }
        // Ramp the section-wide chunk-load fades ONCE per frame, here at the opaque seam (which fires before the OIT
        registry.updateTranslucentFades(Util.getMillis());

        if (visible.visibleCount() == 0) {
            clearFrustumLeaveRegions(visible);
            int prevBufferIndex = currBufferIndex ^ 1;
            currBufferIndex = prevBufferIndex;
            firstFrame = false;
            updatePreviousFrameHistory(visible);
            stagingBuffer.reclaim();
            return true;
        }

        writeHiZUniforms(matrices, mc);

        int prevBufferIndex = currBufferIndex ^ 1;
        long cmdBytesPerRegion = ((long) MAX_COMMANDS_PER_REGION + MAX_TEMPORAL_COMMANDS_PER_REGION) * COMMAND_STRIDE;
        boolean commandCapacityGrew = ensureCommandCapacity(visible.maxVisibleCount(), cmdBytesPerRegion);

        GpuTextureView atlasView = mc.getTextureManager()
                                     .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        passADepthAvailable = TerrainDebug.HIZ_ENABLED && !firstFrame && !commandCapacityGrew && meshDrawStrategy == null;
        meshHizActive = TerrainDebug.HIZ_ENABLED && meshDrawStrategy != null && meshDepthPyramidValid
                && target.width == meshPyramidWidth && target.height == meshPyramidHeight;
        if (passADepthAvailable) {
            drawPassA(matrices, target, colorView, depthView, atlasView, lightmapView, prevBufferIndex, visible);
        }

        runComputeTail(depthView, visible);

        drawMdiAndTemporal(matrices, target, colorView, depthView, atlasView, lightmapView, currBufferIndex, visible);

        // Mesh tier: snapshot this frame's depth into the pyramid for NEXT frame's cull -- DEFERRED to after
        // the engine's opaque instance draws (IndirectDrawManager consumes the stash), so the snapshot holds
        // terrain + entities + opaque instanced VISUALS and big builds occlude terrain. Same one-frame-temporal
        if (meshDrawStrategy != null) {
            deferredMeshRegen = () -> regenerateMeshHizPyramid(target);
        }

        clearFrustumLeaveRegions(visible);

        currBufferIndex = prevBufferIndex;
        firstFrame = false;
        updatePreviousFrameHistory(visible);

        stagingBuffer.reclaim();
        return true;
    }

    public void captureTranslucentArena(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        // Instancing (CPU per-section replay) path: GPU cull is off. lastProjection is captured for uniformity only.
        lastModelView.set(matrices.modelView());
        lastProjection.set(matrices.projection());
        lastModelViewValid = true;
        translucentGpuDriven = false;
        translucentBatch.reset();
        long now = Util.getMillis();

        SortedRenderLists renderLists = manager.getRenderLists();
        ReversibleObjectArrayIterator<ChunkRenderList> it = renderLists.iterator(false);
        while (it.hasNext()) {
            ChunkRenderList renderList = it.next();
            RenderRegion region = renderList.getRegion();

            markAnimatedSpritesActive(region, renderList);

            // Translucent capture is id-INDEPENDENT: the OIT replay keys each section off origin + the live geometry
            int regionId = region.getId();

            int originChunkX = region.getChunkX();
            int originChunkY = region.getChunkY();
            int originChunkZ = region.getChunkZ();
            var resources = region.getResources();
            GpuBuffer geometryGpuBuffer = resources == null ? null : resources.getGeometryBuffer();

            SectionRenderDataStorage translucentStorage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
            if (translucentStorage != null && geometryGpuBuffer != null) {
                ByteIterator sectionIt = renderList.sectionsWithGeometryIterator(false);
                while (sectionIt != null && sectionIt.hasNext()) {
                    int s = sectionIt.nextByteAsInt();
                    collectTranslucentSection(translucentStorage, s, regionId, originChunkX, originChunkY,
                            originChunkZ, geometryGpuBuffer, now);
                }
            }
        }
    }

    /**
     * chunk-OIT-only resident path (terrainMode TRANSLUCENT_OIT, INDIRECT): prep the GPU-driven translucent layer WITHOUT the
     * opaque MDI takeover. Runs the same registry maintenance + region collection + fade ramp as {@link #drawOpaqueSolid}
     */
    public void prepareResidentTranslucent(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        lastModelView.set(matrices.modelView());
        lastProjection.set(matrices.projection());
        lastModelViewValid = true;
        translucentGpuDriven = true;
        captureTranslucent = true;

        registry.flushPendingUploads();

        // Region-level walk: populates translucentRegionBatch (the opaque solid/cutout batches it also fills go unused).
        OpaqueTerrainBatch visible = collectVisibleRegions(manager, null);
        int maxRegionId = Math.max(visible.maxRegionId, translucentRegionBatch.maxRegionId);
        if (maxRegionId >= 0) {
            registry.ensureSectionVisCapacity(maxRegionId + 1);
        }
        registry.updateTranslucentFades(Util.getMillis());

        stagingBuffer.reclaim();
    }

    private void drawPassA(ChunkRenderMatrices matrices, RenderTarget target,
                           GpuTextureView colorView, GpuTextureView depthView, GpuTextureView atlasView,
                           GpuTextureView lightmapView, int bufferIndex, OpaqueTerrainBatch opaque) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GlCompat.pushDebugGroup("flywheel:gl/terrain/pass_a");
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:terrain/pass_a",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("Sampler0", atlasView, atlasSampler);
            pass.bindTexture("Sampler2", lightmapView, lightmapSampler);

            GpuBufferSlice chunkSectionSlice = chunkSectionUniforms.writeUniform(
                    new ChunkSectionUniform(matrices.modelView()));
            pass.setUniform("ChunkSection", chunkSectionSlice);

            pass.setPipeline(TerrainPipelines.solid());
            drawCommandStream(pass, PASS_SOLID, bufferIndex, opaque.solid, false, true);

            pass.setPipeline(TerrainPipelines.cutout());
            drawCommandStream(pass, PASS_CUTOUT, bufferIndex, opaque.cutout, false, true);
        }
        GlCompat.popDebugGroup();

        // Visual depth replay: last frame's culled big-instance draws, depth-only, at the CURRENT camera
        IndirectDrawManager.replayVisualDepth(colorView, depthView);
    }

    private void runComputeTail(GpuTextureView depthView, OpaqueTerrainBatch opaque) {
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_TERRAIN_HIZ_UBO,
                hizUniformBuffer.handle(), 0, hizUniformScratch.size());

        if (passADepthAvailable) {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget tgt = mc.gameRenderer.mainRenderTarget();
            GpuTexture depthTexture = tgt.getDepthTexture();
            if (depthTexture == null) {
                passADepthAvailable = false;
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            } else {
                int depthGlId = ((GlTexture) depthTexture).glId();
                GlCompat.pushDebugGroup("flywheel:gl/terrain/hiz");
                terrainDepthPyramid.regenerate(depthGlId, tgt.width, tgt.height);
                GlCompat.popDebugGroup();
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            }
        } else if (meshHizActive) {
            // Mesh tier reads LAST frame's pyramid, whose regen deferred its texture-fetch barrier so the build could
            // overlap last frame's post-terrain rendering. Issue it now, before region/section_test sample the pyramid.
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        } else {
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        }

        runComputeTailForPass(opaque.solid);
        // Validate INSIDE the tail, per pass: regionVis is shared pass-transient state (the next pass's
        if (TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER) {
            gpuBuilderValidator.validateGpuBuilder(opaque.solid, currBufferIndex);
        }
        runComputeTailForPass(opaque.cutout);
        if (TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER) {
            gpuBuilderValidator.validateGpuBuilder(opaque.cutout, currBufferIndex);
        }

        passADepthAvailable = false;
    }

    private void runComputeTailForPass(VisibleRegionBatch visible) {
        if (visible.count == 0) {
            return;
        }

        packAndUploadRegionInput(visible);
        clearRegionVis(visible.count);
        clearCommandCounts(visible.passIndex, currBufferIndex, visible.count);

        int pass = visible.passIndex;
        packAndBindSceneUbo(regionInputBuffers[pass].deviceAddress(), registry.sectionDataAddress(pass),
                regionVisBuffer.deviceAddress(), registry.sectionVisAddress(pass),
                commandBuffers[pass][currBufferIndex].deviceAddress(),
                regionCommandCounts[pass][currBufferIndex].deviceAddress(),
                registry.presentMaskAddress(pass), visible.count);

        GlCompat.pushDebugGroup("flywheel:gl/terrain/cull");
        regionTestProgram.bind();
        bindCullPyramid();
        GL43.glDispatchCompute(Mth.positiveCeilDiv(visible.count, 64), 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        sectionTestProgram.bind();
        bindCullPyramid();
        GL43.glDispatchCompute(visible.count, 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        commandBuilderProgram.bind();
        GL43.glDispatchCompute(visible.count, 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
        GlCompat.popDebugGroup();
    }

    /**
     * Pack the 7 resident device pointers + region count into the cull scene UBO at {@link #BINDING_TERRAIN_SCENE_UBO}.
     * Re-uploaded every call: a grown buffer is recreated = a new address.
     */
    private void packAndBindSceneUbo(long addr0, long addr8, long addr16, long addr24, long addr32, long addr40,
                                     long addr48, int count) {
        long ptr = terrainSceneUboScratch.ptr();
        MemoryUtil.memPutLong(ptr + 0L, addr0);
        MemoryUtil.memPutLong(ptr + 8L, addr8);
        MemoryUtil.memPutLong(ptr + 16L, addr16);
        MemoryUtil.memPutLong(ptr + 24L, addr24);
        MemoryUtil.memPutLong(ptr + 32L, addr32);
        MemoryUtil.memPutLong(ptr + 40L, addr40);
        MemoryUtil.memPutLong(ptr + 48L, addr48);
        MemoryUtil.memPutInt(ptr + 56L, count);
        if (!terrainSceneUboAllocated) {
            terrainSceneUbo.upload(terrainSceneUboScratch);
            terrainSceneUboAllocated = true;
        } else {
            terrainSceneUbo.uploadSpan(0, terrainSceneUboScratch);
        }
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_TERRAIN_SCENE_UBO,
                terrainSceneUbo.handle(), 0, TERRAIN_SCENE_UBO_BYTES);
    }

    private void bindCullPyramid() {
        if (passADepthAvailable || meshHizActive) {
            terrainDepthPyramid.bindForCull();
        } else {
            terrainDepthPyramid.bindPlaceholder();
        }
    }

    private void regenerateMeshHizPyramid(RenderTarget target) {
        if (!TerrainDebug.HIZ_ENABLED) {
            meshDepthPyramidValid = false;
            return;
        }
        GpuTexture depthTexture = target.getDepthTexture();
        if (depthTexture == null) {
            meshDepthPyramidValid = false;
            return;
        }
        // Defer the pyramid's texture-fetch barrier: this build is consumed by NEXT frame's runComputeTail, so the
        // imageStores need not be visible until then. Skipping the immediate barrier lets the upper-mip reduction
        terrainDepthPyramid.regenerate(((GlTexture) depthTexture).glId(), target.width, target.height, true);
        meshDepthPyramidValid = true;
        meshPyramidWidth = target.width;
        meshPyramidHeight = target.height;
    }

    private void drawMdiAndTemporal(ChunkRenderMatrices matrices, RenderTarget target,
                                    GpuTextureView colorView, GpuTextureView depthView, GpuTextureView atlasView,
                                    GpuTextureView lightmapView, int bufferIndex, OpaqueTerrainBatch opaque) {
        ObjIntConsumer<TerrainDrawDispatcher> strategy = meshDrawStrategy;
        if (strategy != null) {
            GlCompat.pushDebugGroup("flywheel:gl/terrain/opaque");
            drawMeshStrategy(strategy, bufferIndex, opaque);
            GlCompat.popDebugGroup();
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GlCompat.pushDebugGroup("flywheel:gl/terrain/opaque");
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:terrain/opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("Sampler0", atlasView, atlasSampler);
            pass.bindTexture("Sampler2", lightmapView, lightmapSampler);

            GpuBufferSlice chunkSectionSlice = chunkSectionUniforms.writeUniform(
                    new ChunkSectionUniform(matrices.modelView()));
            pass.setUniform("ChunkSection", chunkSectionSlice);

            pass.setPipeline(TerrainPipelines.solid());
            drawCommandStream(pass, PASS_SOLID, bufferIndex, opaque.solid, false, false);

            pass.setPipeline(TerrainPipelines.cutout());
            drawCommandStream(pass, PASS_CUTOUT, bufferIndex, opaque.cutout, false, false);

            pass.setPipeline(TerrainPipelines.solid());
            drawCommandStream(pass, PASS_SOLID, bufferIndex, opaque.solid, true, false);

            pass.setPipeline(TerrainPipelines.cutout());
            drawCommandStream(pass, PASS_CUTOUT, bufferIndex, opaque.cutout, true, false);
        }
        GlCompat.popDebugGroup();
    }

    private void drawMeshStrategy(ObjIntConsumer<TerrainDrawDispatcher> strategy, int bufferIndex,
                                  OpaqueTerrainBatch opaque) {
        boundBufferIndex = bufferIndex;
        if (opaque.solid.count > 0) {
            boundBatch = opaque.solid;
            // No re-pack: regionInputBuffers[PASS_SOLID] still holds the solid cull's slot->regionId map (per-pass
            strategy.accept(this, PASS_SOLID);
        }
        if (opaque.cutout.count > 0) {
            boundBatch = opaque.cutout;
            strategy.accept(this, PASS_CUTOUT);
        }
        boundBatch = null;
    }

    private void drawCommandStream(RenderPass pass, int passIndex, int bufferIndex, VisibleRegionBatch visible,
                                   boolean temporalSlice, boolean previousSlotReplay) {
        long cmdBytesPerRegion = ((long) MAX_COMMANDS_PER_REGION + MAX_TEMPORAL_COMMANDS_PER_REGION) * COMMAND_STRIDE;
        long temporalByteOffset = (long) MAX_COMMANDS_PER_REGION * COMMAND_STRIDE;
        int maxCmds = temporalSlice ? MAX_TEMPORAL_COMMANDS_PER_REGION : MAX_COMMANDS_PER_REGION;

        GlBufferType.DRAW_INDIRECT_BUFFER.bind(commandBuffers[passIndex][bufferIndex].handle());
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, regionCommandCounts[passIndex][bufferIndex].handle());

        if (visible.maxIndexCount <= 0) {
            return;
        }
        sharedIndexBuffer.ensureCapacity(visible.maxIndexCount);
        GpuBuffer sharedIndexGpu = sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }

        // M5-hybrid hoist: Mojang's encoder re-applies every sampler/draw-buffer/scissor per drawIndexed, so prime
        boolean primed = false;
        for (int slot = 0; slot < visible.count; slot++) {
            if (visible.maxSectionIndexCount[slot] <= 0) {
                continue;
            }

            int commandSlot = previousSlotReplay ? visible.previousCommandSlots[slot] : slot;
            if (commandSlot < 0) {
                continue;
            }

            GpuBuffer geometryGpuBuffer = visible.geometryBuffers[slot];
            if (geometryGpuBuffer == null || geometryGpuBuffer.isClosed()) {
                continue;
            }

            long regionCmdOffset = (long) commandSlot * cmdBytesPerRegion + (temporalSlice ? temporalByteOffset : 0);

            GpuBufferSlice originSlice = visible.originSlices[slot];
            if (originSlice == null) {
                originSlice = regionOriginUniforms.writeUniform(new RegionChunkOriginUniform(
                        visible.originChunkX[slot], visible.originChunkY[slot], visible.originChunkZ[slot]));
                visible.originSlices[slot] = originSlice;
            }

            if (!primed) {
                pass.setVertexBuffer(0, geometryGpuBuffer.slice());
                pass.setIndexBuffer(sharedIndexGpu, IndexType.INT);
                pass.drawIndexed(0, 0, 0, 0, 0);
                primed = true;
            }

            // One raw call vs a full per-region encoder trySetup.
            GL43.glBindVertexBuffer(0, gpuBufferHandle(geometryGpuBuffer), 0L, TERRAIN_VERTEX_STRIDE);
            // Region origin: raw UBO bind at the shader's explicit binding 10 (not a Mojang bind-group uniform).
            GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_REGION_CHUNK_ORIGIN,
                    gpuBufferHandle(originSlice.buffer()), originSlice.offset(), originSlice.length());
            // This region's 256-float section-fade slice; the opaque vsh reads it per-vertex by section id and the
            // fsh applies the fog-ward chunk-load fade (settled sections read 1.0 -> the mix is a no-op). Same
            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BINDING_SECTION_FADE_VIS,
                    registry.translucentVisHandle(),
                    (long) visible.regionIds[slot] * REGION_SIZE * Float.BYTES,
                    (long) REGION_SIZE * Float.BYTES);

            long countOffset = (long) commandSlot * 8L + (temporalSlice ? 4L : 0L);
            GlCompat.multiDrawElementsIndirectCount(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                    regionCmdOffset, countOffset, maxCmds, COMMAND_STRIDE);
        }
    }

    private int previousCommandSlot(int passIndex, int regionId, int originChunkX, int originChunkY, int originChunkZ,
                                    int geometryHandle, int indexHandle) {
        int prevSlot = previousSlotByRegionId[passIndex].get(regionId);
        if (prevSlot < 0 || prevSlot >= previousVisibleCounts[passIndex]) {
            return -1;
        }
        if (previousRegionIds[passIndex][prevSlot] != regionId
                || previousOriginChunkX[passIndex][prevSlot] != originChunkX
                || previousOriginChunkY[passIndex][prevSlot] != originChunkY
                || previousOriginChunkZ[passIndex][prevSlot] != originChunkZ
                || previousGeometryHandles[passIndex][prevSlot] != geometryHandle
                || previousIndexHandles[passIndex][prevSlot] != indexHandle) {
            return -1;
        }
        return prevSlot;
    }

    private void updatePreviousFrameHistory(OpaqueTerrainBatch opaque) {
        updatePreviousFrameHistory(opaque.solid);
        updatePreviousFrameHistory(opaque.cutout);
    }

    private void updatePreviousFrameHistory(VisibleRegionBatch visible) {
        int passIndex = visible.passIndex;
        previousSlotByRegionId[passIndex].clear();
        lastFrameVisibleRegions[passIndex].clear();
        previousVisibleCounts[passIndex] = visible.count;
        for (int i = 0; i < visible.count; i++) {
            int regionId = visible.regionIds[i];
            previousRegionIds[passIndex][i] = regionId;
            previousOriginChunkX[passIndex][i] = visible.originChunkX[i];
            previousOriginChunkY[passIndex][i] = visible.originChunkY[i];
            previousOriginChunkZ[passIndex][i] = visible.originChunkZ[i];
            previousGeometryHandles[passIndex][i] = visible.geometryHandles[i];
            previousIndexHandles[passIndex][i] = visible.indexHandles[i];
            previousSlotByRegionId[passIndex].put(regionId, i);
            lastFrameVisibleRegions[passIndex].add(regionId);
        }
    }

    private OpaqueTerrainBatch collectVisibleRegions(RenderSectionManager manager,
                                                     @Nullable Collection<RenderRegion> selfEnum) {
        solidBatch.reset();
        cutoutBatch.reset();
        translucentRegionBatch.reset();

        if (selfEnum != null) {
            // CPU region-frustum prefilter built from the SAME viewProjection the GPU region_test uses (lastProjection
            Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
            Vec3 camPos = camera.isInitialized() ? camera.position() : Vec3.ZERO;
            selfEnumFrustum.set(selfEnumViewProj.set(lastProjection).mul(lastModelView));
            for (RenderRegion region : selfEnum) {
                if (!regionInFrustum(region, camPos.x, camPos.y, camPos.z)) {
                    continue;
                }
                markAnimatedSpritesResident(region);
                collectRegion(region);
            }
        } else {
            ReversibleObjectArrayIterator<ChunkRenderList> it = manager.getRenderLists().iterator(false);
            while (it.hasNext()) {
                ChunkRenderList renderList = it.next();
                RenderRegion region = renderList.getRegion();
                markAnimatedSpritesActive(region, renderList);
                collectRegion(region);
            }
        }
        opaqueBatch.refreshMaxRegionId();
        return opaqueBatch;
    }

    /**
     * Region-AABB (8x4x8 chunks = 128x64x128 blocks) frustum test, camera-relative, matching the GPU region_test: the
     * HiZ applies {@code viewProjection x (regionWorld - cameraWorld)}, so the CPU tests the camera-relative AABB
     */
    private boolean regionInFrustum(RenderRegion region, double camX, double camY, double camZ) {
        float minX = (float) ((region.getChunkX() << 4) - camX);
        float minY = (float) ((region.getChunkY() << 4) - camY);
        float minZ = (float) ((region.getChunkZ() << 4) - camZ);
        return selfEnumFrustum.testAab(minX, minY, minZ, minX + 128f, minY + 64f, minZ + 128f);
    }

    private void collectRegion(RenderRegion region) {
        int regionId = region.getId();
        if (regionId < 0) {
            return;
        }
        int originChunkX = region.getChunkX();
        int originChunkY = region.getChunkY();
        int originChunkZ = region.getChunkZ();
        if (!registry.isLive(regionId)) {
            primeRegion(region, regionId, originChunkX, originChunkY, originChunkZ);
            return;
        }
        var resources = region.getResources();
        GpuBuffer geometryGpuBuffer = resources == null ? null : resources.getGeometryBuffer();
        if (geometryGpuBuffer == null || geometryGpuBuffer.isClosed()) {
            return;
        }
        int geoHandle = gpuBufferHandle(geometryGpuBuffer);
        int idxHandle = gpuBufferHandle(resources.getIndexBuffer());

        boolean hasSolid = registry.hasPresent(PASS_SOLID, regionId);
        if (hasSolid && solidBatch.count < MAX_VISIBLE_REGIONS) {
            appendSlot(solidBatch, regionId, originChunkX, originChunkY, originChunkZ,
                    geometryGpuBuffer, geoHandle, idxHandle, region);
        } else if (hasSolid) {
            warnRegionCapOnce();
        }
        if (cutoutBatch.count < MAX_VISIBLE_REGIONS && registry.hasPresent(PASS_CUTOUT, regionId)) {
            appendSlot(cutoutBatch, regionId, originChunkX, originChunkY, originChunkZ,
                    geometryGpuBuffer, geoHandle, idxHandle, region);
        }
        if (captureTranslucent && registry.hasTranslucentPresent(regionId)) {
            translucentRegionBatch.add(regionId, originChunkX, originChunkY, originChunkZ,
                    geometryGpuBuffer, registry.translucentMaxIndexCount(regionId), region);
        }
    }

    private void collectTranslucentSection(SectionRenderDataStorage storage, int s, int regionId,
                                           int originChunkX, int originChunkY, int originChunkZ,
                                           GpuBuffer geometryGpuBuffer, long now) {
        if (geometryGpuBuffer == null || geometryGpuBuffer.isClosed()) {
            return;
        }
        long pMeshData = storage.getDataPointer(s);
        long vertexCount = TerrainSectionMath.sumVertexCount(pMeshData);
        if (vertexCount == 0) {
            return;
        }
        int indexCount = (int) ((vertexCount >> 2) * 6L);
        int baseVertex = (int) SectionRenderDataUnsafe.getBaseVertex(pMeshData);
        float visibility = translucentVisibility(regionId, s, originChunkX, originChunkY, originChunkZ, now);
        translucentBatch.add(geometryGpuBuffer, originChunkX, originChunkY, originChunkZ,
                baseVertex, indexCount, visibility, s);
    }

    /**
     * Per-section chunk-load fade visibility. The engine owns the timer because we draw Sodium's raw geometry
     * arena, not its shaded output, so Sodium's own fade is not in effect.
     */
    private float translucentVisibility(int regionId, int sectionId, int originChunkX, int originChunkY,
                                        int originChunkZ, long now) {
        if (regionId < 0) {
            return 1.0f;
        }
        long key = ((long) regionId << 8) | (sectionId & 0xFFL);
        long firstSeen = translucentFirstSeenMillis.get(key);
        long fadeMs;
        if (firstSeen == 0L) {
            translucentFirstSeenMillis.put(key, now);
            firstSeen = now;
            fadeMs = TerrainSectionMath.computeFadeDuration(
                    (originChunkX << 4) + TerrainSectionMath.localSectionX(sectionId) * 16,
                    (originChunkY << 4) + TerrainSectionMath.localSectionY(sectionId) * 16,
                    (originChunkZ << 4) + TerrainSectionMath.localSectionZ(sectionId) * 16);
            translucentFadeDurationMillis.put(key, fadeMs);
        } else {
            fadeMs = translucentFadeDurationMillis.get(key);
        }
        if (fadeMs == 0L) {
            return 1.0f;
        }
        return Mth.clamp((float) (now - firstSeen) / (float) fadeMs, 0.0f, 1.0f);
    }

    private void pruneTranslucentFade(int regionId) {
        long base = (long) regionId << 8;
        for (int s = 0; s < REGION_SIZE; s++) {
            long key = base | s;
            translucentFirstSeenMillis.remove(key);
            translucentFadeDurationMillis.remove(key);
        }
    }

    private void appendSlot(VisibleRegionBatch out, int regionId, int originChunkX, int originChunkY, int originChunkZ,
                            GpuBuffer geometryGpuBuffer, int geoHandle, int idxHandle, RenderRegion region) {
        int idx = out.count;
        int passIndex = out.passIndex;
        int regionMaxIndexCount = registry.maxIndexCount(passIndex, regionId);

        out.regionIds[idx] = regionId;
        out.originChunkX[idx] = originChunkX;
        out.originChunkY[idx] = originChunkY;
        out.originChunkZ[idx] = originChunkZ;
        out.storages[idx] = TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER
                ? region.getStorage(TerrainSectionRegistry.passFor(passIndex)) : null;
        out.geometryBuffers[idx] = geometryGpuBuffer;
        out.geometryHandles[idx] = geoHandle;
        out.indexHandles[idx] = idxHandle;
        out.maxSectionIndexCount[idx] = regionMaxIndexCount;
        if (regionMaxIndexCount > out.maxIndexCount) {
            out.maxIndexCount = regionMaxIndexCount;
        }
        out.previousCommandSlots[idx] = previousCommandSlot(passIndex, regionId,
                originChunkX, originChunkY, originChunkZ, geoHandle, idxHandle);
        out.count = idx + 1;
    }

    private void primeRegion(RenderRegion region, int regionId, int originX, int originY, int originZ) {
        var resources = region.getResources();
        if (resources == null) {
            return;
        }
        GpuBuffer geometry = resources.getGeometryBuffer();
        int geoHandle = gpuBufferHandle(geometry);
        SectionRenderDataStorage solidStorage = region.getStorage(DefaultTerrainRenderPasses.SOLID);
        SectionRenderDataStorage cutoutStorage = region.getStorage(DefaultTerrainRenderPasses.CUTOUT);
        SectionRenderDataStorage translucentStorage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (solidStorage == null && cutoutStorage == null && translucentStorage == null) {
            return;
        }
        registry.noteRegionIdentity(regionId, originX, originY, originZ, geoHandle);
        for (int s = 0; s < REGION_SIZE; s++) {
            long dataPtrSolid = solidStorage == null ? 0L : solidStorage.getDataPointer(s);
            long dataPtrCutout = cutoutStorage == null ? 0L : cutoutStorage.getDataPointer(s);
            long dataPtrTranslucent = translucentStorage == null ? 0L : translucentStorage.getDataPointer(s);
            registry.onSectionMeshed(regionId, originX, originY, originZ, s,
                    dataPtrSolid, dataPtrCutout, dataPtrTranslucent, geoHandle);
        }
    }

    /**
     * Pack the region-input uvec4 per slot and upload. Packing (see terrain_region_test.comp): x0 =
     * (originChunkX & 0xFFFF) | ((originChunkZ & 0xFFFF) << 16); x1 = originChunkY & 0xFFFF; x2 = regionId.
     */
    private void packAndUploadRegionInput(VisibleRegionBatch visible) {
        long ptr = regionInputScratch.ptr();
        for (int i = 0; i < visible.count; i++) {
            long dst = ptr + (long) i * REGION_INPUT_STRIDE;
            int ox = visible.originChunkX[i];
            int oy = visible.originChunkY[i];
            int oz = visible.originChunkZ[i];
            int x0 = (ox & 0xFFFF) | ((oz & 0xFFFF) << 16);
            int x1 = oy & 0xFFFF;
            MemoryUtil.memPutInt(dst, x0);
            MemoryUtil.memPutInt(dst + 4L, x1);
            MemoryUtil.memPutInt(dst + 8L, visible.regionIds[i]);
            MemoryUtil.memPutInt(dst + 12L, 0xFFFFFFFF);
        }
        regionInputBuffers[visible.passIndex].uploadSpan(0, regionInputScratch.ptr(),
                (long) visible.count * REGION_INPUT_STRIDE);
    }

    private void clearRegionVis(int count) {
        GL45.glClearNamedBufferSubData(regionVisBuffer.handle(), GL30.GL_R32UI, 0, (long) count * VISIBILITY_STRIDE,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, CLEAR_R32UI);
    }

    private void clearCommandCounts(int passIndex, int bufferIndex, int count) {
        GL45.glClearNamedBufferSubData(regionCommandCounts[passIndex][bufferIndex].handle(), GL30.GL_RG32UI,
                0, (long) count * 8L, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, CLEAR_RG32UI);
    }

    private void writeHiZUniforms(ChunkRenderMatrices matrices, Minecraft mc) {
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.isInitialized() ? camera.position() : Vec3.ZERO;
        int cbx = Mth.floor(camPos.x);
        int cby = Mth.floor(camPos.y);
        int cbz = Mth.floor(camPos.z);
        // Captured for the debug CPU oracle, which must reproduce the command builder's camera facing mask from
        // the exact values this frame's UBO carried.
        gpuBuilderValidator.captureCamera(cbx, cby, cbz,
                (float) (camPos.x - cbx), (float) (camPos.y - cby), (float) (camPos.z - cbz));

        long ptr = hizUniformScratch.ptr();
        Matrix4f viewProj = new Matrix4f(matrices.projection()).mul(matrices.modelView());
        viewProj.get(0, MemoryUtil.memByteBuffer(ptr, 64));
        MemoryUtil.memPutFloat(ptr + 64L, (float) (camPos.x - cbx));
        MemoryUtil.memPutFloat(ptr + 68L, (float) (camPos.y - cby));
        MemoryUtil.memPutFloat(ptr + 72L, (float) (camPos.z - cbz));
        // The two .w pads carry the framebuffer pixel size: cameraPosAndPad.w = width, cameraBlockPosAndPad.w =
        RenderTarget hizTarget = mc.gameRenderer.mainRenderTarget();
        MemoryUtil.memPutFloat(ptr + 76L, (float) hizTarget.width);
        MemoryUtil.memPutInt(ptr + 80L, cbx);
        MemoryUtil.memPutInt(ptr + 84L, cby);
        MemoryUtil.memPutInt(ptr + 88L, cbz);
        MemoryUtil.memPutInt(ptr + 92L, hizTarget.height);
        hizUniformBuffer.upload(hizUniformScratch.ptr(), hizUniformScratch.size());
    }

    private void clearFrustumLeaveRegions(OpaqueTerrainBatch opaque) {
        if (firstFrame) {
            return;
        }
        clearFrustumLeaveRegions(opaque.solid);
        clearFrustumLeaveRegions(opaque.cutout);
    }

    private void clearFrustumLeaveRegions(VisibleRegionBatch visible) {
        IntOpenHashSet now = frustumLeaveScratch;
        now.clear();
        for (int i = 0; i < visible.count; i++) {
            now.add(visible.regionIds[i]);
        }
        // Primitive iterator: an enhanced-for over the fastutil IntSet would box each element to Integer.
        IntIterator iter = lastFrameVisibleRegions[visible.passIndex].iterator();
        while (iter.hasNext()) {
            int rid = iter.nextInt();
            if (!now.contains(rid)) {
                registry.clearSectionVisRegion(visible.passIndex, rid);
            }
        }
    }

    @Nullable
    public SodiumTerrainOitReplay translucentOitReplay() {
        if (!lastModelViewValid) {
            return null;
        }
        int count = translucentGpuDriven ? translucentRegionBatch.count : translucentBatch.count;
        return count == 0 ? null : oitReplay;
    }

    private void replayTranslucentOit(RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                                      GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                                      GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler) {
        if (translucentBatch.count == 0 || !lastModelViewValid) {
            return;
        }
        // Defensive only: translucentOitReplay() (the ownership predicate the seam cancels on) is already non-null
        if (translucentBatch.maxIndexCount <= 0) {
            return;
        }
        sharedIndexBuffer.ensureCapacity(translucentBatch.maxIndexCount);
        GpuBuffer sharedIndexGpu = sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }

        // Live BLOCK atlas: LINEAR+mipmap (== vanilla chunkLayerSampler); the sharp look comes from
        // flw_chunk_oit.fsh's texel-snap/RGSS, which REQUIRES the mipmap sampler.
        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                                            .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();

        pass.setPipeline(OitPipelines.chunkSodiumProducer(mode));
        pass.bindTexture("Sampler0", atlasView, atlasSampler);
        pass.bindTexture("Sampler2", lightmapView, clampLinear);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);
        pass.setIndexBuffer(sharedIndexGpu, IndexType.INT);

        GpuBuffer boundGeometry = null;
        for (int i = 0; i < translucentBatch.count; i++) {
            GpuBuffer geometry = translucentBatch.geometryBuffers[i];
            if (geometry == null || geometry.isClosed()) {
                continue;
            }
            if (geometry != boundGeometry) {
                pass.setVertexBuffer(0, geometry.slice());
                boundGeometry = geometry;
            }

            GpuBufferSlice originSlice = regionOriginUniforms.writeUniform(new RegionChunkOriginUniform(
                    translucentBatch.originChunkX[i], translucentBatch.originChunkY[i],
                    translucentBatch.originChunkZ[i]));
            GpuBufferSlice chunkSectionSlice = translucentChunkSectionUniforms.writeUniform(
                    new ChunkSectionUniform(lastModelView, translucentBatch.visibility[i]));
            GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_REGION_CHUNK_ORIGIN,
                    gpuBufferHandle(originSlice.buffer()), originSlice.offset(), originSlice.length());
            pass.setUniform("ChunkSection", chunkSectionSlice);

            pass.drawIndexed(translucentBatch.indexCount[i], 1, 0, translucentBatch.baseVertex[i], 0);
        }
    }

    private void prepareTranslucentCull(GpuTexture depthTexture, int width, int height) {
        int count = translucentRegionBatch.count;
        if (count == 0) {
            return;
        }
        // The section-wide fades were already ramped this frame at the opaque seam (drawOpaqueSolid), which fires
        writeTranslucentHiZUniforms(width, height);
        ensureTranslucentCommandCapacity(count);
        packAndUploadTranslucentRegionInput();
        packAndUploadTranslucentLiveMask();
        clearTranslucentRegionVis(count);
        clearTranslucentCommandCount(count);

        // Regenerate the pyramid from the OIT-target depth -- the SAME depth the producers depth-test against (under
        int depthGlId = ((GlTexture) depthTexture).glId();
        GlCompat.pushDebugGroup("flywheel:gl/terrain/translucent_hiz");
        translucentDepthPyramid.regenerate(depthGlId, width, height);
        GlCompat.popDebugGroup();
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_TERRAIN_HIZ_UBO,
                translucentHizUniformBuffer.handle(), 0, translucentHizUniformScratch.size());

        uploadAndBindTranslucentSceneUbo(count);
        translucentDepthPyramid.bindForCull();

        GlCompat.pushDebugGroup("flywheel:gl/terrain/translucent_cull");
        regionTestProgram.bind();
        GL43.glDispatchCompute(Mth.positiveCeilDiv(count, 64), 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        TerrainTranslucentMeshDrawStrategy strategy = translucentMeshDrawStrategy;
        if (strategy != null) {
            strategy.prepareCommands(this);
            GlCompat.popDebugGroup();
            return;
        }

        // Fused per-section cull + two-stream command build. It derefs sectionData/translucentVis/command/count from the
        // translucent scene UBO bound above (no per-buffer glBindBufferRange) -- the same Nvidium bindless scheme the
        // opaque command_builder uses. Only the depth pyramid (T10) + the HiZ UBO (8) stay bound.
        translucentCullBuildProgram.bind();
        GL43.glDispatchCompute(count, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
        GlCompat.popDebugGroup();
    }

    private void uploadAndBindTranslucentSceneUbo(int count) {
        packAndBindSceneUbo(translucentRegionInputBuffer.deviceAddress(), registry.translucentSectionDataAddress(),
                translucentRegionVisBuffer.deviceAddress(), registry.translucentVisAddress(),
                translucentCommandBuffer.deviceAddress(), translucentCommandCount.deviceAddress(),
                0L, // unused by region_test + the translucent cull-build
                count);
    }

    private void replayTranslucentOitGpu(RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                                         GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                                         GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler) {
        if (translucentRegionBatch.count == 0 || translucentRegionBatch.maxIndexCount <= 0) {
            return;
        }
        // Mesh-backend draw seam: when a translucent strategy is registered, hand the per-mode producer draw to it
        // INSTEAD of CrankShaft's glMultiDrawElementsIndirectCount two-stream loop. The emit-half (prepareCommands) has
        // already written the mesh-task command stream(s) this frame; the strategy reads them off this dispatcher's
        TerrainTranslucentMeshDrawStrategy strategy = translucentMeshDrawStrategy;
        if (strategy != null) {
            // The mesh producer draws this mode for the settled stream always, the fading stream only when something is
            // mid-fade. The mesh tier routes all its texture/sampler state through GlStateManager, so it leaves the
            // encoder's cache coherent for the next Mojang pass with no separate priming draw.
            strategy.draw(this, pass, mode, framebuffer, false, lightmapView, blueNoiseView,
                    clampLinear, oitSampler, noiseSampler);
            if (registry.hasActiveFades()) {
                strategy.draw(this, pass, mode, framebuffer, true, lightmapView, blueNoiseView,
                        clampLinear, oitSampler, noiseSampler);
            }
            return;
        }
        sharedIndexBuffer.ensureCapacity(translucentRegionBatch.maxIndexCount);
        GpuBuffer sharedIndexGpu = sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }
        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                                            .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        // ChunkSection carries ONLY ModelViewMat now (the fade is GPU-resident); one slice per frame, shared across
        // every region + both streams + the 3 OIT producer modes.
        GpuBufferSlice chunkSectionSlice = translucentChunkSectionUniforms.writeUniform(
                new ChunkSectionUniform(lastModelView));

        drawTranslucentStream(pass, mode, framebuffer, lightmapView, blueNoiseView, clampLinear, oitSampler,
                noiseSampler, atlasView, chunkSectionSlice, sharedIndexGpu, false);
        if (registry.hasActiveFades()) {
            drawTranslucentStream(pass, mode, framebuffer, lightmapView, blueNoiseView, clampLinear, oitSampler,
                    noiseSampler, atlasView, chunkSectionSlice, sharedIndexGpu, true);
        }
    }

    private void drawTranslucentStream(RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                                       GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                                       GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler,
                                       GpuTextureView atlasView, GpuBufferSlice chunkSectionSlice,
                                       GpuBuffer sharedIndexGpu, boolean fading) {
        pass.setPipeline(OitPipelines.chunkSodiumProducer(mode, fading));
        pass.bindTexture("Sampler0", atlasView, atlasSampler);
        pass.bindTexture("Sampler2", lightmapView, clampLinear);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);
        pass.setUniform("ChunkSection", chunkSectionSlice);
        // Set the shared INTEGER index buffer AFTER setPipeline (it owns the encoder's index/VAO cache).
        pass.setIndexBuffer(sharedIndexGpu, IndexType.INT);

        drawTranslucentRegionSlots(pass, fading);
    }

    private void drawTranslucentRegionSlots(RenderPass pass, boolean fading) {
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(translucentCommandBuffer.handle());
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, translucentCommandCount.handle());

        long streamByteOffset = fading ? TRANSLUCENT_FADING_BYTE_OFFSET : 0L;
        long countByteOffset = fading ? 4L : 0L;
        int count = translucentRegionBatch.count;
        boolean primed = false;
        for (int slot = 0; slot < count; slot++) {
            GpuBuffer geometry = translucentRegionBatch.geometryBuffers[slot];
            if (geometry == null || geometry.isClosed()) {
                continue;
            }
            GpuBufferSlice originSlice = translucentRegionBatch.originSlices[slot];
            if (originSlice == null) {
                originSlice = regionOriginUniforms.writeUniform(new RegionChunkOriginUniform(
                        translucentRegionBatch.originChunkX[slot], translucentRegionBatch.originChunkY[slot],
                        translucentRegionBatch.originChunkZ[slot]));
                translucentRegionBatch.originSlices[slot] = originSlice;
            }
            if (!primed) {
                pass.setVertexBuffer(0, geometry.slice());
                pass.drawIndexed(0, 0, 0, 0, 0);
                primed = true;
            }
            GL43.glBindVertexBuffer(0, gpuBufferHandle(geometry), 0L, TERRAIN_VERTEX_STRIDE);
            GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, BINDING_REGION_CHUNK_ORIGIN,
                    gpuBufferHandle(originSlice.buffer()), originSlice.offset(), originSlice.length());
            if (fading) {
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BINDING_SECTION_FADE_VIS,
                        registry.translucentVisHandle(),
                        (long) translucentRegionBatch.regionIds[slot] * REGION_SIZE * Float.BYTES,
                        (long) REGION_SIZE * Float.BYTES);
            }
            long regionCmdOffset = (long) slot * TRANSLUCENT_REGION_COMMAND_BYTES + streamByteOffset;
            long countOffset = (long) slot * 8L + countByteOffset;
            GlCompat.multiDrawElementsIndirectCount(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                    regionCmdOffset, countOffset, MAX_TRANSLUCENT_COMMANDS_PER_STREAM, COMMAND_STRIDE);
        }
    }

    private void replayTranslucentOitInsert(RenderPass pass, OitInsertMode mode, GpuTextureView lightmapView,
                                            GpuSampler clampLinear) {
        if (translucentRegionBatch.count == 0 || translucentRegionBatch.maxIndexCount <= 0) {
            return;
        }
        TerrainTranslucentMeshDrawStrategy strategy = translucentMeshDrawStrategy;
        if (strategy != null) {
            strategy.drawInsert(this, pass, mode, false, lightmapView, clampLinear);
            if (registry.hasActiveFades()) {
                strategy.drawInsert(this, pass, mode, true, lightmapView, clampLinear);
            }
            return;
        }
        sharedIndexBuffer.ensureCapacity(translucentRegionBatch.maxIndexCount);
        GpuBuffer sharedIndexGpu = sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }
        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                                            .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuBufferSlice chunkSectionSlice = translucentChunkSectionUniforms.writeUniform(
                new ChunkSectionUniform(lastModelView));

        drawTranslucentStreamInsert(pass, mode, lightmapView, clampLinear, atlasView, chunkSectionSlice, sharedIndexGpu,
                false);
        if (registry.hasActiveFades()) {
            drawTranslucentStreamInsert(pass, mode, lightmapView, clampLinear, atlasView, chunkSectionSlice,
                    sharedIndexGpu, true);
        }
    }

    private void drawTranslucentStreamInsert(RenderPass pass, OitInsertMode mode, GpuTextureView lightmapView,
                                             GpuSampler clampLinear, GpuTextureView atlasView,
                                             GpuBufferSlice chunkSectionSlice,
                                             GpuBuffer sharedIndexGpu, boolean fading) {
        pass.setPipeline(OitPipelines.chunkSodiumMlab(mode, fading));
        pass.bindTexture("Sampler0", atlasView, atlasSampler);
        pass.bindTexture("Sampler2", lightmapView, clampLinear);
        pass.setUniform("ChunkSection", chunkSectionSlice);
        pass.setIndexBuffer(sharedIndexGpu, IndexType.INT);

        drawTranslucentRegionSlots(pass, fading);
    }

    private void writeTranslucentHiZUniforms(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.isInitialized() ? camera.position() : Vec3.ZERO;
        int cbx = Mth.floor(camPos.x);
        int cby = Mth.floor(camPos.y);
        int cbz = Mth.floor(camPos.z);
        long ptr = translucentHizUniformScratch.ptr();
        Matrix4f viewProj = new Matrix4f(lastProjection).mul(lastModelView);
        viewProj.get(0, MemoryUtil.memByteBuffer(ptr, 64));
        MemoryUtil.memPutFloat(ptr + 64L, (float) (camPos.x - cbx));
        MemoryUtil.memPutFloat(ptr + 68L, (float) (camPos.y - cby));
        MemoryUtil.memPutFloat(ptr + 72L, (float) (camPos.z - cbz));
        // The .w pads carry the OIT-target pixel size for the mesh tier's per-quad sub-pixel cull (mirrors
        MemoryUtil.memPutFloat(ptr + 76L, (float) width);
        MemoryUtil.memPutInt(ptr + 80L, cbx);
        MemoryUtil.memPutInt(ptr + 84L, cby);
        MemoryUtil.memPutInt(ptr + 88L, cbz);
        MemoryUtil.memPutInt(ptr + 92L, height);
        translucentHizUniformBuffer.upload(translucentHizUniformScratch.ptr(), translucentHizUniformScratch.size());
    }

    private void packAndUploadTranslucentRegionInput() {
        long ptr = translucentRegionInputScratch.ptr();
        int count = translucentRegionBatch.count;
        for (int i = 0; i < count; i++) {
            long dst = ptr + (long) i * REGION_INPUT_STRIDE;
            int ox = translucentRegionBatch.originChunkX[i];
            int oy = translucentRegionBatch.originChunkY[i];
            int oz = translucentRegionBatch.originChunkZ[i];
            int x0 = (ox & 0xFFFF) | ((oz & 0xFFFF) << 16);
            int x1 = oy & 0xFFFF;
            MemoryUtil.memPutInt(dst, x0);
            MemoryUtil.memPutInt(dst + 4L, x1);
            MemoryUtil.memPutInt(dst + 8L, translucentRegionBatch.regionIds[i]);
            MemoryUtil.memPutInt(dst + 12L, 0xFFFFFFFF);
        }
        // Resident immutable storage (pre-sized to the worst case): subData into the live prefix, never realloc.
        translucentRegionInputBuffer.uploadSpan(0, translucentRegionInputScratch.ptr(),
                (long) count * REGION_INPUT_STRIDE);
    }

    /**
     * Build + upload the per-frame SLOT-keyed per-section live mask (8 uints/slot): bit s is set iff section s
     * has live vertexCount > 0 in the region's CURRENT Sodium translucent storage.
     */
    private void packAndUploadTranslucentLiveMask() {
        long ptr = translucentLiveMaskScratch.ptr();
        int count = translucentRegionBatch.count;
        MemoryUtil.memSet(ptr, 0, (long) count * LIVE_MASK_WORDS_PER_SLOT * Integer.BYTES);
        for (int i = 0; i < count; i++) {
            RenderRegion region = translucentRegionBatch.regions[i];
            if (region == null) {
                continue;
            }
            SectionRenderDataStorage storage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
            if (storage == null) {
                continue;
            }
            long slotBase = ptr + (long) i * LIVE_MASK_WORDS_PER_SLOT * Integer.BYTES;
            for (int s = 0; s < REGION_SIZE; s++) {
                long pMeshData = storage.getDataPointer(s);
                long vertexCount = TerrainSectionMath.sumVertexCount(pMeshData);
                if (vertexCount == 0) {
                    continue;
                }
                long wordPtr = slotBase + (long) (s >> 5) * Integer.BYTES;
                MemoryUtil.memPutInt(wordPtr, MemoryUtil.memGetInt(wordPtr) | (1 << (s & 31)));
            }
        }
        translucentLiveMaskBuffer.upload(translucentLiveMaskScratch.ptr(),
                (long) count * LIVE_MASK_WORDS_PER_SLOT * Integer.BYTES);
    }

    private void ensureTranslucentCommandCapacity(int count) {
        if (count <= translucentCommandRegionCap) {
            return;
        }
        int newCap = Math.max(count, Math.max(TRANSLUCENT_COMMAND_REGION_CAP_INIT, translucentCommandRegionCap * 2));
        // Resident: a grow recreates the storage (new device address), re-fetched into the translucent cull scene UBO
        zeroFillResident(translucentCommandBuffer, TRANSLUCENT_REGION_COMMAND_BYTES * newCap);
        zeroFillResident(translucentCommandCount, (long) newCap * 8L);
        translucentCommandRegionCap = newCap;
    }

    private void clearTranslucentRegionVis(int count) {
        GL45.glClearNamedBufferSubData(translucentRegionVisBuffer.handle(), GL30.GL_R32UI, 0,
                (long) count * VISIBILITY_STRIDE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, CLEAR_R32UI);
    }

    private void clearTranslucentCommandCount(int count) {
        GL45.glClearNamedBufferSubData(translucentCommandCount.handle(), GL30.GL_RG32UI, 0,
                (long) count * 8L, GL30.GL_RG_INTEGER, GL11.GL_UNSIGNED_INT, CLEAR_RG32UI);
    }

    public void delete() {
        freeAll();
    }

    // Shared with the constructor's rollback, where the four body-assigned resources may still be null.
    private void freeAll() {
        sharedIndexBuffer.delete();
        regionInputBuffers[PASS_SOLID].delete();
        regionInputBuffers[PASS_CUTOUT].delete();
        regionVisBuffer.delete();
        for (GlResidentBuffer[] passBuffers : commandBuffers) {
            for (GlResidentBuffer b : passBuffers) {
                b.delete();
            }
        }
        for (GlResidentBuffer[] passCounts : regionCommandCounts) {
            for (GlResidentBuffer b : passCounts) {
                b.delete();
            }
        }
        if (registry != null) {
            registry.delete();
        }
        if (stagingBuffer != null) {
            stagingBuffer.delete();
        }
        if (terrainDepthPyramid != null) {
            terrainDepthPyramid.delete();
        }
        if (translucentDepthPyramid != null) {
            translucentDepthPyramid.delete();
        }
        translucentRegionInputBuffer.delete();
        translucentRegionVisBuffer.delete();
        translucentCommandBuffer.delete();
        translucentCommandCount.delete();
        translucentHizUniformBuffer.delete();
        translucentLiveMaskBuffer.delete();
        regionInputScratch.free();
        translucentRegionInputScratch.free();
        translucentLiveMaskScratch.free();
        translucentHizUniformScratch.free();
        hizUniformBuffer.delete();
        hizUniformScratch.free();
        terrainSceneUbo.delete();
        terrainSceneUboScratch.free();
        regionOriginUniforms.close();
        chunkSectionUniforms.close();
        translucentChunkSectionUniforms.close();
    }

    public static final class VisibleRegionBatch {
        public final int passIndex;
        public final int[] regionIds = new int[MAX_VISIBLE_REGIONS];
        public final int[] originChunkX = new int[MAX_VISIBLE_REGIONS];
        public final int[] originChunkY = new int[MAX_VISIBLE_REGIONS];
        public final int[] originChunkZ = new int[MAX_VISIBLE_REGIONS];
        public final GpuBuffer[] geometryBuffers = new GpuBuffer[MAX_VISIBLE_REGIONS];
        public final int[] maxSectionIndexCount = new int[MAX_VISIBLE_REGIONS];
        final SectionRenderDataStorage[] storages = new SectionRenderDataStorage[MAX_VISIBLE_REGIONS];
        final int[] geometryHandles = new int[MAX_VISIBLE_REGIONS];
        final int[] indexHandles = new int[MAX_VISIBLE_REGIONS];
        final int[] previousCommandSlots = new int[MAX_VISIBLE_REGIONS];
        // Per-slot u_RegionChunkOrigin slice cached for the frame: written once, reused across the 4 MDI sub-passes.
        final GpuBufferSlice[] originSlices = new GpuBufferSlice[MAX_VISIBLE_REGIONS];
        public int count = 0;
        public int maxIndexCount = 0;

        public VisibleRegionBatch(int passIndex) {
            this.passIndex = passIndex;
            Arrays.fill(previousCommandSlots, -1);
        }

        public void reset() {
            // Null only the slots that were populated last frame; the GpuBuffer refs must not pin closed arenas.
            for (int i = 0; i < count; i++) {
                geometryBuffers[i] = null;
                storages[i] = null;
                previousCommandSlots[i] = -1;
                originSlices[i] = null;
            }
            count = 0;
            maxIndexCount = 0;
        }
    }

    private static final class OpaqueTerrainBatch {
        final VisibleRegionBatch solid;
        final VisibleRegionBatch cutout;
        int maxRegionId = -1;

        OpaqueTerrainBatch(VisibleRegionBatch solid, VisibleRegionBatch cutout) {
            this.solid = solid;
            this.cutout = cutout;
        }

        private static int solidMaxRegionId(VisibleRegionBatch batch) {
            int max = -1;
            for (int i = 0; i < batch.count; i++) {
                max = Math.max(max, batch.regionIds[i]);
            }
            return max;
        }

        void refreshMaxRegionId() {
            maxRegionId = Math.max(solidMaxRegionId(solid), solidMaxRegionId(cutout));
        }

        int visibleCount() {
            return solid.count + cutout.count;
        }

        int maxVisibleCount() {
            return Math.max(solid.count, cutout.count);
        }
    }

    public static final class TranslucentBatch {
        public int count = 0;
        public GpuBuffer[] geometryBuffers = new GpuBuffer[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int[] originChunkX = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int[] originChunkY = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int[] originChunkZ = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int[] baseVertex = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int[] indexCount = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public float[] visibility = new float[TRANSLUCENT_SECTION_INITIAL_CAP];
        // Local section index (0..255) of each entry, for the GPU OIT cull's section-AABB reconstruction.
        public int[] sectionIndex = new int[TRANSLUCENT_SECTION_INITIAL_CAP];
        public int maxIndexCount = 0;
        int capacity = TRANSLUCENT_SECTION_INITIAL_CAP;

        public void reset() {
            for (int i = 0; i < count; i++) {
                geometryBuffers[i] = null;
            }
            count = 0;
            maxIndexCount = 0;
        }

        void ensureCapacity(int needed) {
            if (needed <= capacity) {
                return;
            }
            int newCap = Math.max(needed, capacity * 2);
            geometryBuffers = Arrays.copyOf(geometryBuffers, newCap);
            originChunkX = Arrays.copyOf(originChunkX, newCap);
            originChunkY = Arrays.copyOf(originChunkY, newCap);
            originChunkZ = Arrays.copyOf(originChunkZ, newCap);
            baseVertex = Arrays.copyOf(baseVertex, newCap);
            indexCount = Arrays.copyOf(indexCount, newCap);
            visibility = Arrays.copyOf(visibility, newCap);
            sectionIndex = Arrays.copyOf(sectionIndex, newCap);
            capacity = newCap;
        }

        public void add(GpuBuffer geometry, int ocx, int ocy, int ocz, int baseVtx, int idxCount, float vis,
                        int secIdx) {
            ensureCapacity(count + 1);
            geometryBuffers[count] = geometry;
            originChunkX[count] = ocx;
            originChunkY[count] = ocy;
            originChunkZ[count] = ocz;
            baseVertex[count] = baseVtx;
            indexCount[count] = idxCount;
            visibility[count] = vis;
            sectionIndex[count] = secIdx;
            if (idxCount > maxIndexCount) {
                maxIndexCount = idxCount;
            }
            count++;
        }
    }

    public static final class TranslucentRegionBatch {
        // Public for the owned-geometry mesh tier's per-region gather (mirrors VisibleRegionBatch.regionIds).
        public final int[] regionIds = new int[MAX_VISIBLE_REGIONS];
        public final GpuBuffer[] geometryBuffers = new GpuBuffer[MAX_VISIBLE_REGIONS];
        final int[] originChunkX = new int[MAX_VISIBLE_REGIONS];
        final int[] originChunkY = new int[MAX_VISIBLE_REGIONS];
        final int[] originChunkZ = new int[MAX_VISIBLE_REGIONS];
        final GpuBufferSlice[] originSlices = new GpuBufferSlice[MAX_VISIBLE_REGIONS];
        final RenderRegion[] regions = new RenderRegion[MAX_VISIBLE_REGIONS];
        public int count = 0;
        int maxRegionId = -1;
        int maxIndexCount = 0;

        void reset() {
            for (int i = 0; i < count; i++) {
                geometryBuffers[i] = null;
                originSlices[i] = null;
                regions[i] = null;
            }
            count = 0;
            maxIndexCount = 0;
            maxRegionId = -1;
        }

        void add(int regionId, int ocx, int ocy, int ocz, GpuBuffer geometry, int regionMaxIndexCount,
                 RenderRegion region) {
            if (count >= MAX_VISIBLE_REGIONS) {
                return;
            }
            int i = count;
            regionIds[i] = regionId;
            originChunkX[i] = ocx;
            originChunkY[i] = ocy;
            originChunkZ[i] = ocz;
            geometryBuffers[i] = geometry;
            originSlices[i] = null;
            regions[i] = region;
            if (regionMaxIndexCount > maxIndexCount) {
                maxIndexCount = regionMaxIndexCount;
            }
            if (regionId > maxRegionId) {
                maxRegionId = regionId;
            }
            count = i + 1;
        }
    }

    private record RegionChunkOriginUniform(int x, int y, int z) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buf) {
            buf.putInt(x).putInt(y).putInt(z).putInt(0);
        }
    }

    private record ChunkSectionUniform(org.joml.Matrix4fc modelView, float chunkVisibility)
            implements DynamicUniformStorage.DynamicUniform {
        ChunkSectionUniform(org.joml.Matrix4fc modelView) {
            this(modelView, 1.0f);
        }

        @Override
        public void write(ByteBuffer buf) {
            // JOML get(index, buf) writes at the ABSOLUTE byte offset and does NOT advance the buffer position, so
            // the trailing fields MUST use absolute puts as well. Relative puts here would start at position 0 and
            // overwrite the matrix (zeroing ModelViewMat columns 0-1 -> degenerate view -> geometry collapses).
            modelView.get(0, buf);
            buf.putFloat(64, chunkVisibility);
            buf.putInt(68, 0);
            buf.putInt(72, 0).putInt(76, 0);
            buf.putInt(80, 0).putInt(84, 0).putInt(88, 0).putInt(92, 0);
        }
    }

}
