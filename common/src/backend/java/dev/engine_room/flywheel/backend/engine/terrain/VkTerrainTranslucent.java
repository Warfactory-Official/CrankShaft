package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkFoldedOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.TranslucentBatch;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.VisibleRegionBatch;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.caffeinemc.mods.sodium.client.util.iterator.ReversibleObjectArrayIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

final class VkTerrainTranslucent implements SodiumTerrainOitReplay, VkFoldedOitReplay {
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int INDIRECT = VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
    private static final int UNIFORM = VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

    private static final long CS_STRIDE = 256; // >= minUniformBufferOffsetAlignment on all desktop GPUs
    private static final long CANDIDATE_STRIDE = 40; // 10 uints: origin xyz, s, baseVertex, indexCount, geoAddr lo/hi, fadeBits, pad
    private static final long COMMAND_STRIDE = 20;   // VkDrawIndexedIndirectCommand (5 uints)
    private static final long DRAW_DATA_STRIDE = 32; // 8 uints: origin xyz, fadeBits, geoAddr lo/hi, pad, pad
    private static final int INITIAL_SECTIONS = 8192;
    final TranslucentBatch batch = new TranslucentBatch();
    final VisibleRegionBatch regionBatch = new VisibleRegionBatch(2);
    final Matrix4f lastModelView = new Matrix4f();
    private final VkTerrainDrawManager m;
    private final PhaseSet[] phases;
    boolean lastModelViewValid;
    private int phase;

    VkTerrainTranslucent(VkTerrainDrawManager m) {
        this.m = m;
        PhaseSet phase0 = new PhaseSet();
        PhaseSet phase1;
        try {
            phase1 = new PhaseSet();
        } catch (Throwable t) {
            phase0.delete();
            throw t;
        }
        phases = new PhaseSet[]{phase0, phase1};
    }

    private PhaseSet buffers() {
        return phases[phase];
    }

    boolean owns() {
        return lastModelViewValid && (batch.count > 0 || VkTerrainDrawManager.translucentMeshDrawStrategy != null);
    }

    void capture(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        if (VkTerrainDrawManager.translucentMeshDrawStrategy != null) {
            collectRegions(matrices, manager);
            // The mesh tier's task reads translucentVis (the chunk-load fade buffer); terrainMode TRANSLUCENT never
            // runs drawOpaqueSolid's sizing, so size it here (present sections default to 1.0) before the ramp below.
            int maxRid = VkTerrainDrawManager.maxRegionId(regionBatch);
            if (maxRid >= 0) {
                m.registry.ensureSectionVisCapacity(maxRid + 1);
            }
        } else {
            captureSections(manager, matrices);
        }
        m.registry.updateTranslucentFades(net.minecraft.util.Util.getMillis());
    }

    // terrainMode OPAQUE: no translucent capture (owns() stays false, Sodium keeps the layer), but the fade ramp
    void rampFadesOnly() {
        m.registry.updateTranslucentFades(net.minecraft.util.Util.getMillis());
    }

    private void collectRegions(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        phase ^= 1;
        lastModelView.set(matrices.modelView());
        lastModelViewValid = true;
        regionBatch.reset();
        ReversibleObjectArrayIterator<ChunkRenderList> it = manager.getRenderLists().iterator(false);
        while (it.hasNext()) {
            RenderRegion region = it.next().getRegion();
            int regionId = region.getId();
            if (regionId < 0 || !m.registry.isLive(regionId)) {
                continue;
            }
            var resources = region.getResources();
            GpuBuffer geo = resources == null ? null : resources.getGeometryBuffer();
            if (geo == null || geo.isClosed()) {
                continue;
            }
            if (regionBatch.count < VkTerrainDrawManager.MAX_VISIBLE_REGIONS && m.registry.translucentMaxIndexCount(
                    regionId) > 0) {
                int idx = regionBatch.count;
                regionBatch.regionIds[idx] = regionId;
                regionBatch.originChunkX[idx] = region.getChunkX();
                regionBatch.originChunkY[idx] = region.getChunkY();
                regionBatch.originChunkZ[idx] = region.getChunkZ();
                regionBatch.geometryBuffers[idx] = geo;
                regionBatch.storages[idx] = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
                regionBatch.maxSectionIndexCount[idx] = m.registry.translucentMaxIndexCount(regionId);
                regionBatch.count = idx + 1;
            }
        }
    }

    void fillLiveMask(long ptr) {
        int words = TerrainDrawDispatcher.REGION_SIZE / Integer.SIZE;
        MemoryUtil.memSet(ptr, 0, (long) regionBatch.count * words * Integer.BYTES);
        for (int i = 0; i < regionBatch.count; i++) {
            SectionRenderDataStorage storage = regionBatch.storages[i];
            if (storage == null) {
                continue;
            }
            long slotBase = ptr + (long) i * words * Integer.BYTES;
            for (int s = 0; s < TerrainDrawDispatcher.REGION_SIZE; s++) {
                if (TerrainSectionMath.sumVertexCount(storage.getDataPointer(s)) == 0) {
                    continue;
                }
                long wordPtr = slotBase + (long) (s >> 5) * Integer.BYTES;
                MemoryUtil.memPutInt(wordPtr, MemoryUtil.memGetInt(wordPtr) | (1 << (s & 31)));
            }
        }
    }

    private void captureSections(RenderSectionManager manager, ChunkRenderMatrices matrices) {
        phase ^= 1; // double-buffer the OIT-replay UBOs (read by the async producer-pass draws)
        lastModelView.set(matrices.modelView());
        lastModelViewValid = true;
        batch.reset();
        long now = net.minecraft.util.Util.getMillis();
        ReversibleObjectArrayIterator<ChunkRenderList> it = manager.getRenderLists().iterator(false);
        while (it.hasNext()) {
            ChunkRenderList renderList = it.next();
            RenderRegion region = renderList.getRegion();
            var resources = region.getResources();
            GpuBuffer geo = resources == null ? null : resources.getGeometryBuffer();
            if (geo == null || geo.isClosed()) {
                continue;
            }
            SectionRenderDataStorage storage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
            if (storage == null) {
                continue;
            }
            int regionId = region.getId();
            int ox = region.getChunkX();
            int oy = region.getChunkY();
            int oz = region.getChunkZ();
            ByteIterator sections = renderList.sectionsWithGeometryIterator(false);
            if (sections == null) {
                continue;
            }
            while (sections.hasNext()) {
                collectSection(storage, sections.nextByteAsInt(), geo, ox, oy, oz, regionId, now);
            }
        }
        buildReplayBuffers();
    }

    private void collectSection(SectionRenderDataStorage storage, int s, GpuBuffer geo, int ox, int oy, int oz,
                                int regionId, long now) {
        long pMeshData = storage.getDataPointer(s);
        long vertexCount = TerrainSectionMath.sumVertexCount(pMeshData);
        if (vertexCount == 0) {
            return;
        }
        int indexCount = (int) ((vertexCount >> 2) * 6L);
        int baseVertex = (int) SectionRenderDataUnsafe.getBaseVertex(pMeshData);
        float vis = regionId < 0 ? 1.0F : m.registry.sectionFadeVisibility(regionId, s, now);
        batch.add(geo, ox, oy, oz, baseVertex, indexCount, vis, s);
    }

    /**
     * Build the replay inputs the OIT producer binds ONCE per mode: the shared ModelViewMat (offset 0 of the
     * single-slot ChunkSection UBO) + the candidate buffer the cull compacts into the per-draw data SSBO the
     * bindless vsh indexes by {@code gl_InstanceIndex}.
     */
    private void buildReplayBuffers() {
        int n = batch.count;
        if (n == 0) {
            return;
        }
        PhaseSet b = buffers();
        lastModelView.get(0, MemoryUtil.memByteBuffer(b.chunkSectionUbo.mappedAddress(), 64));
        b.cullInput.ensureCapacity((long) n * CANDIDATE_STRIDE);
        long base = b.cullInput.mappedAddress();
        for (int i = 0; i < n; i++) {
            long slot = base + (long) i * CANDIDATE_STRIDE;
            long geoAddr = m.geoDeviceAddress(((VulkanGpuBuffer) batch.geometryBuffers[i]).vkBuffer());
            MemoryUtil.memPutInt(slot, batch.originChunkX[i]);
            MemoryUtil.memPutInt(slot + 4L, batch.originChunkY[i]);
            MemoryUtil.memPutInt(slot + 8L, batch.originChunkZ[i]);
            MemoryUtil.memPutInt(slot + 12L, batch.sectionIndex[i]);
            MemoryUtil.memPutInt(slot + 16L, batch.baseVertex[i]);
            MemoryUtil.memPutInt(slot + 20L, batch.indexCount[i]);
            MemoryUtil.memPutInt(slot + 24L, (int) geoAddr);
            MemoryUtil.memPutInt(slot + 28L, (int) (geoAddr >>> 32));
            MemoryUtil.memPutFloat(slot + 32L, batch.visibility[i]);
        }
        b.drawCommand.ensureCapacity((long) n * COMMAND_STRIDE);
        b.drawData.ensureCapacity((long) n * DRAW_DATA_STRIDE);
        MemoryUtil.memPutInt(b.cullCountUbo.mappedAddress(), n);
        MemoryUtil.memPutInt(b.drawCount.mappedAddress(), 0);
    }

    @Override
    public void prepareCull(GpuTextureView depthView, int width, int height) {
        VkTerrainTranslucentMeshDrawStrategy strategy = VkTerrainDrawManager.translucentMeshDrawStrategy;
        if (strategy != null) {
            // Publish this frame's resident parity before the translucent cull reads the registry buffers. In
            m.syncResidentMetadata();
            m.hiz.ensureFreshForTranslucentCull(depthView, width, height, m.writer);
            strategy.prepareCull(m, depthView.texture(), width, height);
            return;
        }
        if (batch.count == 0 || !lastModelViewValid) {
            return;
        }
        VkPrograms programs = VkPrograms.get();
        if (programs != null) {
            m.hiz.ensureFreshForTranslucentCull(depthView, width, height, m.writer);
            dispatchCull(programs);
        }
    }

    private void dispatchCull(VkPrograms programs) {
        int n = batch.count;
        PhaseSet b = buffers();
        long pyramidSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache()
                                                              .getClampToEdge(FilterMode.NEAREST)).vkSampler();
        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/translucent_oit_cull");
        VkComputePipeline cull = programs.terrain().translucentOitCullPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, cull.handle());
        m.writer.storage(0, b.cullInput)
                .storage(4, b.drawCommand)
                .storage(5, b.drawCount)
                .storage(6, b.drawData)
                .uniform(8, m.hiz.ubo(m.frameParity))
                .uniform(9, b.cullCountUbo)
                .sampler(10, m.hiz.pyramid.sampledView(), pyramidSampler);
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, cull.layout());
        VK12.vkCmdDispatch(cmd, Mth.positiveCeilDiv(n, 64), 1, 1);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    @Override
    public void replay(RenderPass pass, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                       GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                       GpuSampler noiseSampler) {
        replayOnCmd(((VulkanRenderPass) pass.backend).commandBuffer, mode, framebuffer, lightmapView,
                blueNoiseView, clampLinear, oitSampler, noiseSampler, false);
    }

    @Override
    public void replayFolded(VkCommandBuffer cmd, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                             GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                             GpuSampler noiseSampler) {
        replayOnCmd(cmd, mode, framebuffer, lightmapView, blueNoiseView, clampLinear, oitSampler, noiseSampler, true);
    }

    @Override
    public void replayMlab(VkCommandBuffer cmd, OitInsertMode oitMode, VkMlabBuffers mlab, GpuTextureView lightmapView,
                           GpuSampler clampLinear) {
        VkTerrainTranslucentMeshDrawStrategy strategy = VkTerrainDrawManager.translucentMeshDrawStrategy;
        if (strategy != null) {
            strategy.drawMlab(m, oitMode, cmd, false, mlab, lightmapView, clampLinear);
            if (m.registry.hasActiveFades()) {
                strategy.drawMlab(m, oitMode, cmd, true, mlab, lightmapView, clampLinear);
            }
            return;
        }
        if (batch.count == 0 || !lastModelViewValid || batch.maxIndexCount <= 0) {
            return;
        }
        VkPrograms programs = VkPrograms.get();
        if (programs == null) {
            return;
        }
        m.sharedIndexBuffer.ensureCapacity(batch.maxIndexCount);
        GpuBuffer sharedIndexGpu = m.sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || globals == null) {
            return;
        }

        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                                                   .getTextureView()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache()
                                                            .getClampToEdge(FilterMode.LINEAR, true)).vkSampler();

        PhaseSet b = buffers();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/translucent_mlab/" + oitMode);
        VkGraphicsPipeline pipeline = programs.terrain().translucentMlabPipeline(oitMode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        VK12.vkCmdBindIndexBuffer(cmd, ((VulkanGpuBuffer) sharedIndexGpu).vkBuffer(), 0L, VK12.VK_INDEX_TYPE_UINT32);

        m.writer.storage(1, b.drawData.vkBuffer(), 0L, (long) batch.count * DRAW_DATA_STRIDE)
                .sampler(10, atlasView, atlasSampler)
                .sampler(12, ((VulkanGpuTextureView) lightmapView).vkImageView(),
                        ((VulkanGpuSampler) clampLinear).vkSampler())
                .uniform(16, projection)
                .uniform(18, fog)
                .uniform(20, globals)
                .uniform(21, b.chunkSectionUbo.vkBuffer(), 0L, CS_STRIDE);
        mlab.bind(m.writer);
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        VK12.vkCmdDrawIndexedIndirectCount(cmd,
                b.drawCommand.vkBuffer(), 0L,
                b.drawCount.vkBuffer(), 0L,
                batch.count, (int) COMMAND_STRIDE);
        VkContext.popLabel(cmd);
    }

    /**
     * Replays the captured translucent sections into the open OIT producer pass (once per {@link OitMode}) with the
     * VK Sodium chunk-OIT producer pipeline -- raw VK, like the instance OIT draws, so Sodium's live CompactChunkVertex
     */
    private void replayOnCmd(VkCommandBuffer cmd, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                             GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                             GpuSampler noiseSampler, boolean folded) {
        VkTerrainTranslucentMeshDrawStrategy strategy = VkTerrainDrawManager.translucentMeshDrawStrategy;
        if (strategy != null) {
            VkContext.pushLabel(cmd, "flywheel:vk/terrain/translucent_mesh/" + mode.name);
            strategy.draw(m, mode, cmd, framebuffer, false, lightmapView, blueNoiseView, clampLinear, oitSampler,
                    noiseSampler, folded);
            if (m.registry.hasActiveFades()) {
                strategy.draw(m, mode, cmd, framebuffer, true, lightmapView, blueNoiseView, clampLinear, oitSampler,
                        noiseSampler, folded);
            }
            VkContext.popLabel(cmd);
            return;
        }
        if (batch.count == 0 || !lastModelViewValid || batch.maxIndexCount <= 0) {
            return;
        }
        VkPrograms programs = VkPrograms.get();
        if (programs == null) {
            return;
        }
        m.sharedIndexBuffer.ensureCapacity(batch.maxIndexCount);
        GpuBuffer sharedIndexGpu = m.sharedIndexBuffer.getBufferObject();
        if (sharedIndexGpu == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || globals == null) {
            return;
        }

        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                                                   .getTextureView()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache()
                                                            .getClampToEdge(FilterMode.LINEAR, true)).vkSampler();
        long lightmapVk = ((VulkanGpuTextureView) lightmapView).vkImageView();
        long lightmapSampler = ((VulkanGpuSampler) clampLinear).vkSampler();
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        long depthRangeView = nonDepthRange ? ((VulkanGpuTextureView) framebuffer.depthBoundsView()).vkImageView() : 0L;
        long blueNoiseVk = nonDepthRange ? ((VulkanGpuTextureView) blueNoiseView).vkImageView() : 0L;
        long oitSamplerVk = ((VulkanGpuSampler) oitSampler).vkSampler();
        long noiseSamplerVk = ((VulkanGpuSampler) noiseSampler).vkSampler();
        long[] coeffViews = new long[4];
        if (mode == OitMode.EVALUATE) {
            for (int i = 0; i < 4; i++) {
                coeffViews[i] = ((VulkanGpuTextureView) framebuffer.coefficientsView(i)).vkImageView();
            }
        }

        PhaseSet b = buffers();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/translucent_oit/" + mode.name);
        VkGraphicsPipeline pipeline = programs.terrain().translucentProducerPipeline(mode, folded);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        VK12.vkCmdBindIndexBuffer(cmd, ((VulkanGpuBuffer) sharedIndexGpu).vkBuffer(), 0L, VK12.VK_INDEX_TYPE_UINT32);

        // Bind the descriptor set ONCE for the whole mode; nothing varies per draw. The bindless producer reads each
        // visible section's origin/fade/geoAddr from the compacted draw-data SSBO @1 (by gl_InstanceIndex) and the
        // shared ModelViewMat from the ChunkSection UBO @21. No vertex input, no per-region bind.
        m.writer.storage(1, b.drawData.vkBuffer(), 0L, (long) batch.count * DRAW_DATA_STRIDE)
                .sampler(10, atlasView, atlasSampler)
                .sampler(12, lightmapVk, lightmapSampler)
                .uniform(16, projection)
                .uniform(18, fog)
                .uniform(20, globals)
                .uniform(21, b.chunkSectionUbo.vkBuffer(), 0L, CS_STRIDE);
        if (nonDepthRange) {
            if (folded) {
                m.writer.inputAttachment(14, depthRangeView);
            } else {
                m.writer.sampler(14, depthRangeView, oitSamplerVk);
            }
            m.writer.sampler(15, blueNoiseVk, noiseSamplerVk);
        }
        if (mode == OitMode.EVALUATE) {
            for (int c = 0; c < 4; c++) {
                if (folded) {
                    m.writer.inputAttachment(24 + c, coeffViews[c]);
                } else {
                    m.writer.sampler(24 + c, coeffViews[c], oitSamplerVk);
                }
            }
        }
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        VK12.vkCmdDrawIndexedIndirectCount(cmd,
                b.drawCommand.vkBuffer(), 0L,
                b.drawCount.vkBuffer(), 0L,
                batch.count, (int) COMMAND_STRIDE);
        VkContext.popLabel(cmd);
    }

    void delete() {
        phases[0].delete();
        phases[1].delete();
    }

    // GPU-driven OIT replay buffers (vk_indirect), double-buffered by phase: the CPU gather fills cullInput;
    // terrain_translucent_oit_cull.comp HiZ-culls it into a compacted DrawIndexedIndirect stream + a per-draw data
    private static final class PhaseSet {
        final VkBuffer chunkSectionUbo;
        final VkBuffer cullInput;
        final VkBuffer drawCommand;
        final VkBuffer drawData;
        final VkBuffer drawCount;
        final VkBuffer cullCountUbo;

        PhaseSet() {
            VkBuffer[] built = new VkBuffer[6];
            try {
                chunkSectionUbo = built[0] = new VkBuffer(UNIFORM, CS_STRIDE);
                cullInput = built[1] = new VkBuffer(STORAGE, INITIAL_SECTIONS * CANDIDATE_STRIDE);
                drawCommand = built[2] = new VkBuffer(STORAGE | INDIRECT, INITIAL_SECTIONS * COMMAND_STRIDE);
                drawData = built[3] = new VkBuffer(STORAGE, INITIAL_SECTIONS * DRAW_DATA_STRIDE);
                drawCount = built[4] = new VkBuffer(STORAGE | INDIRECT, 16);
                cullCountUbo = built[5] = new VkBuffer(UNIFORM, 16);
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
            chunkSectionUbo.delete();
            cullInput.delete();
            drawCommand.delete();
            drawData.delete();
            drawCount.delete();
            cullCountUbo.delete();
        }
    }
}
