package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.TranslucentBatch;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.VisibleRegionBatch;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.iterator.ReversibleObjectArrayIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Raw-Vulkan FULL terrain tier -- the VK twin of the GL {@code TerrainDrawDispatcher}, selected on a Vulkan host
 * by {@code TerrainDispatchers}.
 */
public final class VkTerrainDrawManager implements TerrainDispatcher {
    static final int REGION_SIZE = 256;
    static final int MAX_VISIBLE_REGIONS = 4096;
    private static final int COLOR_FORMAT = VK12.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int DEPTH_FORMAT = VK12.VK_FORMAT_D32_SFLOAT;
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int INDIRECT = VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
    private static final int UNIFORM = VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    private static final int PASS_SOLID = 0;
    private static final int PASS_CUTOUT = 1;
    private static final int PASS_COUNT = 2;
    private static final int MAX_COMMANDS_PER_REGION = 7 * REGION_SIZE;
    private static final int MAX_TEMPORAL_COMMANDS_PER_REGION = MAX_COMMANDS_PER_REGION;
    private static final int COMMAND_STRIDE = 20;
    private static final int REGION_INPUT_STRIDE = 16;
    private static final long CMD_BYTES_PER_REGION = ((long) MAX_COMMANDS_PER_REGION + MAX_TEMPORAL_COMMANDS_PER_REGION) * COMMAND_STRIDE;
    private static final long REGION_GEO_STRIDE = 8;  // uvec2 arena device address per visible-region slot
    private static final long DRAW_DATA_STRIDE = 32;  // 8 uints: origin xyz, visBase, geoAddr lo/hi, pad, pad
    // Two-phase HiZ phase ids (section_test / mesh emit `phase` field): 0 = single-phase MDI (Pass A / temporal
    private static final int PHASE_MDI = 0;
    private static final int PHASE_1 = 1;
    private static final int PHASE_2 = 2;
    // DIAG bisect: force the opaque / translucent mesh tier OFF (fall back to the proven MDI opaque / CPU translucent
    private static final boolean DISABLE_OPAQUE_MESH = false;
    private static final boolean DISABLE_TRANSLUCENT_MESH = false;
    @Nullable
    static VkTerrainTranslucentMeshDrawStrategy translucentMeshDrawStrategy;
    private static boolean unsupportedLogged;
    private static boolean initFailed;
    @Nullable
    private static VkTerrainMeshDrawStrategy meshDrawStrategy;
    // Last frame's post-visuals pyramid view, handed to VkIndirectDrawManager.generatePyramid (consume-once).
    private static long carriedPyramidView;
    // The stashed rebuild + phase 2 of THIS frame's drawTwoPhase; consumed once per frame at the engine's opaque
    @Nullable
    private static Runnable deferredPhase2;
    public final TerrainSectionRegistry registry;
    final VkDescriptorWriter writer = new VkDescriptorWriter();
    final VkTerrainHiZ hiz;
    final SharedQuadIndexBuffer sharedIndexBuffer = new SharedQuadIndexBuffer(
            SharedQuadIndexBuffer.IndexFormat.INTEGER);
    private final VkTerrainResidentBuffers residentBuffers;
    private final Long2LongOpenHashMap geoAddrCache = new Long2LongOpenHashMap();
    private final VkTerrainTranslucent translucent;
    private final VisibleRegionBatch solidBatch = new VisibleRegionBatch(PASS_SOLID);
    private final VisibleRegionBatch cutoutBatch = new VisibleRegionBatch(PASS_CUTOUT);
    private final CullBuffers[][] cull; // [pass][parity]
    private final VkBuffer[] chunkSectionUbo;
    /**
     * The (batch, parity) cursor the registered mesh strategy reads during a drawOpaque call; null between passes.
     */
    @Nullable
    public VisibleRegionBatch boundBatch;
    public int boundParity;
    public int boundPhase = PHASE_MDI;
    int frameParity;
    private int residentParity;
    private boolean metadataSyncedThisFrame;

    public VkTerrainDrawManager() {
        cull = new CullBuffers[PASS_COUNT][2];
        chunkSectionUbo = new VkBuffer[2];
        try {
            residentBuffers = new VkTerrainResidentBuffers();
            registry = new TerrainSectionRegistry(residentBuffers);
            hiz = new VkTerrainHiZ();
            translucent = new VkTerrainTranslucent(this);
            for (int p = 0; p < PASS_COUNT; p++) {
                cull[p][0] = new CullBuffers();
                cull[p][1] = new CullBuffers();
            }
            chunkSectionUbo[0] = new VkBuffer(UNIFORM, 256);
            chunkSectionUbo[1] = new VkBuffer(UNIFORM, 256);
        } catch (Throwable t) {
            deleteConstructed();
            throw t;
        }
    }

    private static int phaseSlot(int phase) {
        return phase == PHASE_2 ? 1 : 0;
    }

    public static void setMeshDrawStrategy(@Nullable VkTerrainMeshDrawStrategy strategy) {
        meshDrawStrategy = DISABLE_OPAQUE_MESH ? null : strategy;
    }

    public static void setTranslucentMeshDrawStrategy(@Nullable VkTerrainTranslucentMeshDrawStrategy strategy) {
        translucentMeshDrawStrategy = DISABLE_TRANSLUCENT_MESH ? null : strategy;
    }

    public static boolean isSupported() {
        return VkContext.isVulkanHost() && VkCaps.DRAW_INDIRECT_COUNT_NEGOTIATED
                && VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED && VkPrograms.allLoaded() && !initFailed;
    }

    public static void logUnsupportedOnce() {
        if (unsupportedLogged) {
            return;
        }
        unsupportedLogged = true;
        String reason = !VkCaps.DRAW_INDIRECT_COUNT_NEGOTIATED ? "VK_KHR_draw_indirect_count not negotiated by the device"
                : !VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED ? "bufferDeviceAddress not negotiated by the device"
                : !VkPrograms.allLoaded() ? "vk programs did not load"
                : initFailed ? "terrain initialization failed" : "unsupported";
        FlwBackend.LOGGER.warn("Flywheel VK terrain disabled; Sodium terrain will render normally ({})", reason);
    }

    public static void disableAfterInitFailure(RuntimeException e) {
        initFailed = true;
        if (!unsupportedLogged) {
            unsupportedLogged = true;
            FlwBackend.LOGGER.warn(
                    "Flywheel VK terrain disabled after initialization failure; Sodium terrain will render normally",
                    e);
        }
    }

    public static long takeCarriedPyramidView() {
        long view = carriedPyramidView;
        carriedPyramidView = 0L;
        return view;
    }

    public static void runDeferredPhase2() {
        Runnable r = deferredPhase2;
        deferredPhase2 = null;
        if (r != null) {
            r.run();
        }
    }

    static int maxRegionId(VisibleRegionBatch batch) {
        int max = -1;
        for (int i = 0; i < batch.count; i++) {
            max = Math.max(max, batch.regionIds[i]);
        }
        return max;
    }

    private static void computeBarrier(VkCommandBuffer cmd) {
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void packRegionInput(VisibleRegionBatch visible, CullBuffers b) {
        long ptr = b.regionInput.mappedAddress();
        for (int i = 0; i < visible.count; i++) {
            long dst = ptr + (long) i * REGION_INPUT_STRIDE;
            int x0 = (visible.originChunkX[i] & 0xFFFF) | ((visible.originChunkZ[i] & 0xFFFF) << 16);
            MemoryUtil.memPutInt(dst, x0);
            MemoryUtil.memPutInt(dst + 4L, visible.originChunkY[i] & 0xFFFF);
            MemoryUtil.memPutInt(dst + 8L, visible.regionIds[i]);
            MemoryUtil.memPutInt(dst + 12L, 0);
        }
    }

    void syncResidentMetadata() {
        if (metadataSyncedThisFrame) {
            return;
        }
        residentParity ^= 1;
        residentBuffers.setReadParity(residentParity);
        if (residentBuffers.hasDirty(residentParity)) {
            VkCommandBuffer cmd = VkContext.beginCommands();
            VkContext.pushLabel(cmd, "flywheel:vk/terrain/resident_upload");
            residentBuffers.recordUploads(cmd, residentParity);
            VkContext.popLabel(cmd);
            VkContext.submitCommands(cmd);
        }
        metadataSyncedThisFrame = true;
    }

    // Shared by delete() and the constructor's rollback, where any suffix of the resource chain may be null.
    private void deleteConstructed() {
        if (registry != null) {
            registry.delete();
        }
        for (CullBuffers[] perPass : cull) {
            for (CullBuffers b : perPass) {
                if (b != null) {
                    b.delete();
                }
            }
        }
        for (VkBuffer ubo : chunkSectionUbo) {
            if (ubo != null) {
                ubo.delete();
            }
        }
        if (translucent != null) {
            translucent.delete();
        }
        if (hiz != null) {
            hiz.delete();
        }
    }

    @Override
    public boolean drawOpaqueSolid(ChunkRenderMatrices matrices, RenderSectionManager manager,
                                   @Nullable Collection<RenderRegion> selfEnum) {
        VkPrograms programs = VkPrograms.get();
        if (programs == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return false;
        }

        geoAddrCache.clear();
        collect(manager);
        // Size the resident vis/section buffers for the opaque region-id range BEFORE capturing translucent.
        int maxRegionId = Math.max(maxRegionId(solidBatch), maxRegionId(cutoutBatch));
        if (maxRegionId >= 0) {
            registry.ensureSectionVisCapacity(maxRegionId + 1);
        }
        if (BackendConfig.INSTANCE.terrainMode().compositesTranslucent()) {
            translucent.capture(matrices, manager);
        } else {
            translucent.rampFadesOnly();
        }
        if (solidBatch.count == 0 && cutoutBatch.count == 0) {
            return true;
        }

        hiz.pyramid.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        int parity = (frameParity ^= 1);
        // Write the HiZ UBO into THIS frame's parity slot -- the one the mesh draw + cull read via boundParity/parity --
        // AFTER the flip. Writing it pre-flip landed this frame's camera + viewProjection in the OTHER slot, so the
        // mesh-tier opaque transform read LAST frame's matrices: a one-frame lag behind the translucent tier (which
        hiz.writeFrame(matrices, mc, parity);
        // All of this frame's registry writes are done (collect/primeRegion, ensureSectionVisCapacity, fade ramp);
        // fold them into this frame's parity copy + publish it before the cull/mesh/draw read the registry buffers.
        syncResidentMetadata();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        long pyramidSampler = ((VulkanGpuSampler) nearest).vkSampler();
        Frame f = buildFrame(programs, matrices, mc);

        if (f != null) {
            drawTwoPhase(programs, f, meshDrawStrategy, colorView, depthView, parity, pyramidSampler, mc);
        }
        return true;
    }

    private void drawTwoPhase(VkPrograms programs, Frame f, @Nullable VkTerrainMeshDrawStrategy strategy,
                              GpuTextureView colorView, GpuTextureView depthView, int parity, long pyramidSampler,
                              Minecraft mc) {
        boolean mdi = strategy == null;
        VkCommandBuffer cull1 = VkContext.beginCommands();
        computeTail(cull1, programs, solidBatch, parity, pyramidSampler, PHASE_1, mdi);
        computeTail(cull1, programs, cutoutBatch, parity, pyramidSampler, PHASE_1, mdi);
        VkContext.submitCommands(cull1);
        drawPhase(f, strategy, colorView, depthView, parity, mc, PHASE_1);

        deferredPhase2 = () -> {
            // Rebuild the pyramid from the main depth as it stands NOW (post-visuals): seeds phase 2 AND carries to
            // next frame's phase 1. The regen's internal depth-write -> compute-read barrier waits for the prior
            VkCommandBuffer regen = VkContext.beginCommands();
            VkContext.pushLabel(regen, "flywheel:vk/terrain/hiz_pyramid");
            hiz.pyramid.regenerate(regen, ((VulkanGpuTextureView) depthView).vkImageView(), pyramidSampler,
                    programs.downsampleFirstPipeline(), programs.downsampleSecondPipeline(), writer);
            VkContext.popLabel(regen);
            VkContext.submitCommands(regen);

            VkCommandBuffer cull2 = VkContext.beginCommands();
            computeTail(cull2, programs, solidBatch, parity, pyramidSampler, PHASE_2, mdi);
            computeTail(cull2, programs, cutoutBatch, parity, pyramidSampler, PHASE_2, mdi);
            VkContext.submitCommands(cull2);
            drawPhase(f, strategy, colorView, depthView, parity, mc, PHASE_2);

            carriedPyramidView = hiz.pyramid.sampledView();
            hiz.markFresh();
        };
    }

    private void drawPhase(Frame f, @Nullable VkTerrainMeshDrawStrategy strategy, GpuTextureView colorView,
                           GpuTextureView depthView, int parity, Minecraft mc, int phase) {
        if (strategy != null) {
            drawMeshStrategy(strategy, colorView, depthView, parity, mc, phase);
        } else {
            drawPass(f, colorView, depthView, parity, mc, phase);
        }
    }

    private void collect(RenderSectionManager manager) {
        solidBatch.reset();
        cutoutBatch.reset();
        ReversibleObjectArrayIterator<ChunkRenderList> it = manager.getRenderLists().iterator(false);
        while (it.hasNext()) {
            collectRegion(it.next().getRegion());
        }
    }

    private void collectRegion(RenderRegion region) {
        int regionId = region.getId();
        if (regionId < 0) {
            return;
        }
        int ox = region.getChunkX();
        int oy = region.getChunkY();
        int oz = region.getChunkZ();
        var resources = region.getResources();
        GpuBuffer geo = resources == null ? null : resources.getGeometryBuffer();
        // Re-prime on cold start OR arena realloc. When Sodium swaps a region's geometry buffer (grow/replace), the
        // baseVertex offsets cached at the last prime describe the OLD arena layout while the draw binds the LIVE
        int liveHandle = geo instanceof VulkanGpuBuffer vk && !geo.isClosed() ? (int) vk.vkBuffer() : -1;
        if (!registry.isLive(regionId) || registry.cachedGeometryHandle(regionId) != liveHandle) {
            primeRegion(region, regionId, ox, oy, oz);
            return;
        }
        if (geo == null || geo.isClosed()) {
            return;
        }
        if (solidBatch.count < MAX_VISIBLE_REGIONS && registry.hasPresent(PASS_SOLID, regionId)) {
            appendSlot(solidBatch, regionId, ox, oy, oz, geo);
        }
        if (cutoutBatch.count < MAX_VISIBLE_REGIONS && registry.hasPresent(PASS_CUTOUT, regionId)) {
            appendSlot(cutoutBatch, regionId, ox, oy, oz, geo);
        }
    }

    private void appendSlot(VisibleRegionBatch out, int regionId, int ox, int oy, int oz, GpuBuffer geo) {
        int idx = out.count;
        int pass = out.passIndex;
        int mic = registry.maxIndexCount(pass, regionId);
        out.regionIds[idx] = regionId;
        out.originChunkX[idx] = ox;
        out.originChunkY[idx] = oy;
        out.originChunkZ[idx] = oz;
        out.geometryBuffers[idx] = geo;
        out.maxSectionIndexCount[idx] = mic;
        if (mic > out.maxIndexCount) {
            out.maxIndexCount = mic;
        }
        out.count = idx + 1;
    }

    private void primeRegion(RenderRegion region, int regionId, int ox, int oy, int oz) {
        var resources = region.getResources();
        if (resources == null) {
            return;
        }
        GpuBuffer geo = resources.getGeometryBuffer();
        int handle = geo == null || geo.isClosed() || !(geo instanceof VulkanGpuBuffer vk) ? -1 : (int) vk.vkBuffer();
        SectionRenderDataStorage solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
        SectionRenderDataStorage cutout = region.getStorage(DefaultTerrainRenderPasses.CUTOUT);
        SectionRenderDataStorage translucentStorage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (solid == null && cutout == null && translucentStorage == null) {
            return;
        }
        registry.noteRegionIdentity(regionId, ox, oy, oz, handle);
        for (int s = 0; s < REGION_SIZE; s++) {
            registry.onSectionMeshed(regionId, ox, oy, oz, s,
                    solid == null ? 0L : solid.getDataPointer(s),
                    cutout == null ? 0L : cutout.getDataPointer(s),
                    translucentStorage == null ? 0L : translucentStorage.getDataPointer(s), handle);
        }
    }

    private void computeTail(VkCommandBuffer cmd, VkPrograms programs, VisibleRegionBatch visible, int parity,
                             long pyramidSampler, int phase, boolean buildMdi) {
        int pass = visible.passIndex;
        int n = visible.count;
        if (n == 0) {
            return;
        }
        int slot = phaseSlot(phase);
        CullBuffers b = cull[pass][parity];
        packRegionInput(visible, b);
        packRegionGeo(visible, b);
        // NO CPU clear of regionVis: region_test writes every slot < regionCount, so a memset is redundant -- AND
        long rcPtr = b.regionCountUbo[slot].mappedAddress();
        MemoryUtil.memPutInt(rcPtr, n);
        MemoryUtil.memPutInt(rcPtr + 4L, phase);

        b.command.ensureCapacity((long) n * CMD_BYTES_PER_REGION);
        b.drawData.ensureCapacity((long) n * MAX_COMMANDS_PER_REGION * 2L * DRAW_DATA_STRIDE);
        MemoryUtil.memPutLong(b.count.mappedAddress(), 0L);

        // Both phases read this manager's own pyramid: phase 1 the CARRIED copy (last frame's post-visuals
        long pyramidView = hiz.pyramid.sampledView();

        VkContext.pushLabel(cmd, "flywheel:vk/terrain/cull/" + (pass == PASS_SOLID ? "solid" : "cutout"));
        VkComputePipeline region = programs.terrain().regionTestPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, region.handle());
        writer.storage(0, b.regionInput)
              .storage(2, b.regionVis);
        bindHizAndCount(b, parity, slot);
        writer.sampler(10, pyramidView, pyramidSampler);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, region.layout());
        VK12.vkCmdDispatch(cmd, Mth.positiveCeilDiv(n, 64), 1, 1);
        computeBarrier(cmd);

        VkComputePipeline section = programs.terrain().sectionTestPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, section.handle());
        writer.storage(0, b.regionInput)
              .storage(1, registry.sectionDataVkBuffer(pass), 0L, registry.sectionDataByteCapacity(pass))
              .storage(2, b.regionVis)
              .storage(3, registry.sectionVisVkBuffer(pass), 0L, registry.sectionVisByteSize())
              .storage(6, registry.presentMaskVkBuffer(pass), 0L, registry.presentMaskByteCapacity(pass));
        bindHizAndCount(b, parity, slot);
        writer.sampler(10, pyramidView, pyramidSampler);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, section.layout());
        VK12.vkCmdDispatch(cmd, n, 1, 1);
        computeBarrier(cmd);

        if (buildMdi) {
            VkComputePipeline builder = programs.terrain().commandBuilderPipeline();
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, builder.handle());
            writer.storage(0, b.regionInput)
                  .storage(1, registry.sectionDataVkBuffer(pass), 0L, registry.sectionDataByteCapacity(pass))
                  .storage(2, b.regionVis)
                  .storage(3, registry.sectionVisVkBuffer(pass), 0L, registry.sectionVisByteSize())
                  .storage(4, b.command)
                  .storage(5, b.count)
                  .storage(6, b.regionGeo)
                  .storage(7, b.drawData);
            bindHizAndCount(b, parity, slot);
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, builder.layout());
            VK12.vkCmdDispatch(cmd, n, 1, 1);
            // dst COMPUTE_SHADER too: the mesh tier's emit (a SEPARATE compute dispatch, later submit) reads this cull
            VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
        }
        VkContext.popLabel(cmd);
    }

    private void bindHizAndCount(CullBuffers b, int parity, int slot) {
        writer.uniform(8, hiz.ubo(parity))
              .uniform(9, b.regionCountUbo[slot]);
    }

    private void packRegionGeo(VisibleRegionBatch visible, CullBuffers b) {
        long ptr = b.regionGeo.mappedAddress();
        for (int i = 0; i < visible.count; i++) {
            GpuBuffer geo = visible.geometryBuffers[i];
            long addr = geo == null || geo.isClosed() ? 0L : geoDeviceAddress(((VulkanGpuBuffer) geo).vkBuffer());
            MemoryUtil.memPutLong(ptr + (long) i * REGION_GEO_STRIDE, addr);
        }
    }

    @Nullable
    private Frame buildFrame(VkPrograms programs, ChunkRenderMatrices matrices, Minecraft mc) {
        int maxIndex = Math.max(solidBatch.maxIndexCount, cutoutBatch.maxIndexCount);
        if (maxIndex <= 0) {
            return null;
        }
        sharedIndexBuffer.ensureCapacity(maxIndex);
        GpuBuffer sharedIndexGpu = sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return null;
        }
        long indexVk = ((VulkanGpuBuffer) sharedIndexGpu).vkBuffer();

        // ChunkSection UBO: only ModelViewMat (offset 0) is read by terrain_solid.vsh; the rest is unused.
        MemoryUtil.memSet(chunkSectionUbo[frameParity].mappedAddress(), 0, chunkSectionUbo[frameParity].sizeBytes());
        new Matrix4f(matrices.modelView()).get(0,
                MemoryUtil.memByteBuffer(chunkSectionUbo[frameParity].mappedAddress(), 64));

        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || globals == null) {
            return null;
        }
        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                                                   .getTextureView()).vkImageView();
        long lightmapView = ((VulkanGpuTextureView) mc.gameRenderer.lightmap()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache()
                                                            .getClampToEdge(FilterMode.LINEAR, true)).vkSampler();
        long loSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache()
                                                         .getClampToEdge(FilterMode.LINEAR)).vkSampler();
        return new Frame(programs, indexVk, projection, fog, globals, atlasView, lightmapView, atlasSampler, loSampler);
    }

    private void drawPass(Frame f, GpuTextureView colorView, GpuTextureView depthView, int parity, Minecraft mc,
                          int phase) {
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // MDI opaque terrain: vanilla entities depth-test + blend into the same target next -> framebuffer-producer
        boolean temporal = phase == PHASE_2;
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:vk/terrain", colorView, Optional.empty(),
                depthView, OptionalDouble.empty())) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            VkCmd.setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/terrain/mdi");
            drawCommandStream(cmd, f, solidBatch, temporal, parity);
            drawCommandStream(cmd, f, cutoutBatch, temporal, parity);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void drawMeshStrategy(VkTerrainMeshDrawStrategy strategy, GpuTextureView colorView,
                                  GpuTextureView depthView, int parity, Minecraft mc, int phase) {
        boundParity = parity;
        boundPhase = phase;
        // Emit-half (compute) runs BEFORE the pass opens -- compute is illegal inside dynamic rendering. Each pass'
        if (solidBatch.count > 0) {
            boundBatch = solidBatch;
            strategy.prepareEmit(this, PASS_SOLID);
        }
        if (cutoutBatch.count > 0) {
            boundBatch = cutoutBatch;
            strategy.prepareEmit(this, PASS_CUTOUT);
        }
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:vk/terrain/mesh", colorView, Optional.empty(),
                depthView, OptionalDouble.empty())) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            VkCmd.setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh");
            if (solidBatch.count > 0) {
                boundBatch = solidBatch;
                strategy.drawOpaque(this, PASS_SOLID, cmd);
            }
            if (cutoutBatch.count > 0) {
                boundBatch = cutoutBatch;
                strategy.drawOpaque(this, PASS_CUTOUT, cmd);
            }
            boundBatch = null;
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    // ---- Mesh-tier state accessors: valid during a strategy.drawOpaque call; the cull buffers are per-pass + indexed
    // by the bound parity, so the tier passes the strategy's passIndex and reads the live slot for this frame. ----
    public long regionInputVk(int pass) {
        return cull[pass][boundParity].regionInput.vkBuffer();
    }

    public long regionInputBytes(int pass) {
        return cull[pass][boundParity].regionInput.sizeBytes();
    }

    public long regionVisVk(int pass) {
        return cull[pass][boundParity].regionVis.vkBuffer();
    }

    public long regionVisBytes(int pass) {
        return cull[pass][boundParity].regionVis.sizeBytes();
    }

    public long hizUboVk() {
        return hiz.ubo(boundParity).vkBuffer();
    }

    public long hizUboBytes() {
        return hiz.ubo(boundParity).sizeBytes();
    }

    public long regionCountUboVk(int pass) {
        return cull[pass][boundParity].regionCountUbo[phaseSlot(boundPhase)].vkBuffer();
    }

    public long regionCountUboBytes(int pass) {
        return cull[pass][boundParity].regionCountUbo[phaseSlot(boundPhase)].sizeBytes();
    }

    public long chunkSectionUboVk() {
        return chunkSectionUbo[frameParity].vkBuffer();
    }

    public long chunkSectionUboSize() {
        return chunkSectionUbo[frameParity].sizeBytes();
    }

    public long terrainPyramidView() {
        return hiz.pyramid.sampledView();
    }

    public Matrix4f boundModelView() {
        return translucent.lastModelView;
    }

    public TranslucentBatch translucentBatch() {
        return translucent.batch;
    }

    public VisibleRegionBatch translucentRegionBatch() {
        return translucent.regionBatch;
    }

    public void fillTranslucentLiveMask(long ptr) {
        translucent.fillLiveMask(ptr);
    }

    // Global MDI: ONE vkCmdDrawIndexedIndirectCount + ONE descriptor push for the whole pass x phase. The builder
    private void drawCommandStream(VkCommandBuffer cmd, Frame f, VisibleRegionBatch visible, boolean temporal,
                                   int commandParity) {
        int pass = visible.passIndex;
        int n = visible.count;
        if (n == 0) {
            return;
        }
        CullBuffers b = cull[pass][commandParity];
        VkGraphicsPipeline pipeline = f.programs().terrain()
                                       .drawPipeline(pass == PASS_CUTOUT, COLOR_FORMAT, DEPTH_FORMAT);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        VK12.vkCmdBindIndexBuffer(cmd, f.indexVk(), 0L, VK12.VK_INDEX_TYPE_UINT32);

        writer.storage(1, registry.translucentVisVkBuffer(), 0L, registry.translucentVisByteSize())
              .storage(2, b.drawData)
              .sampler(10, f.atlasView(), f.atlasSampler())
              .sampler(12, f.lightmapView(), f.loSampler())
              .uniform(16, f.projection())
              .uniform(18, f.fog())
              .uniform(20, f.globals())
              .uniform(21, chunkSectionUbo[frameParity]);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        int maxCmds = n * MAX_COMMANDS_PER_REGION;
        long cmdOffset = temporal ? (long) maxCmds * COMMAND_STRIDE : 0L;
        long countOffset = temporal ? 4L : 0L;
        VK12.vkCmdDrawIndexedIndirectCount(cmd, b.command.vkBuffer(), cmdOffset,
                b.count.vkBuffer(), countOffset, maxCmds, COMMAND_STRIDE);
    }

    long geoDeviceAddress(long vkBuffer) {
        long addr = geoAddrCache.get(vkBuffer);
        if (addr == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack).sType$Default()
                                                                          .buffer(vkBuffer);
                addr = VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), info);
            }
            geoAddrCache.put(vkBuffer, addr);
        }
        return addr;
    }

    @Override
    public void prepareResidentTranslucent(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        // terrainMode TRANSLUCENT: Sodium draws opaque, the engine owns only the translucent layer. The full HiZ
        geoAddrCache.clear();
        translucent.capture(matrices, manager);
        hiz.writeTranslucentFrame(matrices, Minecraft.getInstance());
    }

    @Override
    public void captureTranslucentArena(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        geoAddrCache.clear();
        translucent.capture(matrices, manager);
    }

    @Override
    @Nullable
    public SodiumTerrainOitReplay translucentOitReplay() {
        // Non-null is the engine's translucent-ownership predicate (the seam cancels Sodium's translucent draw on it):
        // the captured CPU per-section batch, OR a registered mesh tier (which culls/draws its own translucent terrain).
        return translucent.owns() ? translucent : null;
    }

    @Override
    public void publishRegistry() {
        registry.publish();
    }

    @Override
    public void unpublishRegistry() {
        registry.unpublish();
    }

    @Override
    public void endFrame() {
        translucent.lastModelViewValid = false;
        metadataSyncedThisFrame = false;
    }

    @Override
    public void delete() {
        deferredPhase2 = null;
        carriedPyramidView = 0L;
        sharedIndexBuffer.delete();
        deleteConstructed();
    }

    // Every per-frame host-mapped buffer is double-buffered by parity: Mojang runs 2 frames in flight, and both the
    // transient cull (async submit -- VkContext.submitCommands does not fence) and the frame-cmd draws read frame N's
    static final class CullBuffers {
        final VkBuffer regionInput;
        final VkBuffer regionVis;
        final VkBuffer command;
        final VkBuffer count;
        // Global MDI per-draw plumbing: regionGeo carries each visible slot's arena device address into the command
        final VkBuffer regionGeo;
        final VkBuffer drawData;
        // Per-PHASE-SLOT 16-byte {regionCount, phase} UBOs. Two-phase HiZ writes a distinct `phase` value for phase 1
        final VkBuffer[] regionCountUbo;

        CullBuffers() {
            // 8 sequential native allocations; a mid-chain VMA OOM must free the earlier ones before propagating.
            VkBuffer[] built = new VkBuffer[8];
            try {
                regionInput = built[0] = new VkBuffer(STORAGE, MAX_VISIBLE_REGIONS * REGION_INPUT_STRIDE);
                regionVis = built[1] = new VkBuffer(STORAGE, MAX_VISIBLE_REGIONS * Integer.BYTES);
                command = built[2] = new VkBuffer(STORAGE | INDIRECT, CMD_BYTES_PER_REGION);
                count = built[3] = new VkBuffer(STORAGE | INDIRECT, 8);
                regionGeo = built[4] = new VkBuffer(STORAGE, MAX_VISIBLE_REGIONS * REGION_GEO_STRIDE);
                drawData = built[5] = new VkBuffer(STORAGE, MAX_COMMANDS_PER_REGION * 2L * DRAW_DATA_STRIDE);
                regionCountUbo = new VkBuffer[]{built[6] = new VkBuffer(UNIFORM, 16), built[7] = new VkBuffer(UNIFORM,
                        16)};
            } catch (Throwable t) {
                for (VkBuffer b : built) {
                    if (b != null) {
                        b.delete();
                    }
                }
                throw t;
            }
        }

        void delete() {
            regionInput.delete();
            regionVis.delete();
            command.delete();
            count.delete();
            regionGeo.delete();
            drawData.delete();
            regionCountUbo[0].delete();
            regionCountUbo[1].delete();
        }
    }

    private record Frame(VkPrograms programs, long indexVk, GpuBufferSlice projection, GpuBufferSlice fog,
                         GpuBuffer globals, long atlasView, long lightmapView, long atlasSampler, long loSampler) {
    }
}
