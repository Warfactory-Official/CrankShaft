// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the VK_EXT_mesh_shader port + CrankShaft OIT integration)
// Derivative work of Nvidium GlTranslucentTerrainRasterizer (the OIT-producer mesh-task draw scheme).

package me.mlbv.meshlet.mesh.vk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.VisibleRegionBatch;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkMeshPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainTranslucentMeshDrawStrategy;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/**
 * The translucent vk_mesh_shader draw strategy: the VK twin of {@code GlTranslucentTerrainRasterizer}. A peer
 * producer into the engine's OIT chain: {@code prepareCull} emits the mesh-task command stream (outside any pass);
 * {@code draw} runs once per OitMode producer pass, reusing flw_chunk_oit's fragment.
 */
public final class VkTranslucentTerrainRasterizer implements VkTerrainTranslucentMeshDrawStrategy {
    private static final int REGION_INPUT_STRIDE = 16;
    private static final int COMMAND_STRIDE = 32;
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int INDIRECT = VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
    private static final int UNIFORM = VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    private static final int TRANSFER_DST = VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int CACHED_VERT_BYTES = 24;
    private static final int DRAW_COMMAND_BYTES = 16;

    private final VkMeshPipelines pipelines;
    private final VkDescriptorWriter writer = new VkDescriptorWriter();

    // Per-frame SLOT-keyed live-section mask (8 uints/slot) filled by the manager from Sodium's CURRENT translucent
    // storage; the emit AND-gates on it (binding 14) so a stale resident-union mirror slot never emits garbage into
    // the shared OIT -- under ABUFFER that garbage stalls the resolve past vanilla's 5s submit timeout.
    private static final int LIVE_MASK_SLOT_BYTES = VkMeshUtil.REGION_SIZE / Integer.SIZE * Integer.BYTES;

    private final VkBuffer[] regionInput = new VkBuffer[2];
    private final VkBuffer[] command = new VkBuffer[2];
    private final VkBuffer[] compactSections = new VkBuffer[2];
    private final VkBuffer[] geoAddrTable = new VkBuffer[2];
    private final VkBuffer[] liveMask = new VkBuffer[2];
    private final VkBuffer[] modelViewUbo = new VkBuffer[2];
    private final VkBuffer[] cache = new VkBuffer[2];
    private final VkBuffer[] quadFade = new VkBuffer[2];
    private final VkBuffer[] drawCommand = new VkBuffer[2];

    private final Long2LongOpenHashMap geoAddrCache = new Long2LongOpenHashMap();
    private int phase;
    private int count;
    private int cacheMaxQuads;

    @Nullable
    private VkMeshGeometryArena arena;

    public VkTranslucentTerrainRasterizer(VkMeshPipelines pipelines) {
        this.pipelines = pipelines;
        geoAddrCache.defaultReturnValue(0L);
    }

    public void setArena(@Nullable VkMeshGeometryArena arena) {
        this.arena = arena;
    }

    @Override
    public void prepareCull(VkTerrainDrawManager manager, GpuTexture oitDepth, int width, int height) {
        phase ^= 1;
        VisibleRegionBatch batch = manager.translucentRegionBatch();
        count = batch.count;
        cacheMaxQuads = 0;
        if (count == 0) {
            return;
        }
        long totalIndex = 0L;
        for (int i = 0; i < count; i++) {
            totalIndex += manager.registry.translucentIndexCountSum(batch.regionIds[i]);
        }
        cacheMaxQuads = (int) (totalIndex / 6L);

        ensureBuffers(phase, count);
        packRegionInput(batch, phase, count);
        manager.fillTranslucentLiveMask(liveMask[phase].mappedAddress());
        new Matrix4f(manager.boundModelView()).get(0, MemoryUtil.memByteBuffer(modelViewUbo[phase].mappedAddress(), 64));
        if (cacheMaxQuads == 0) {
            return;
        }
        ensureCacheBuffers(phase, cacheMaxQuads);

        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh/translucent_gather");
        if (arena != null) {
            arena.attach(manager.registry);
            buildGeoAddrTableOwned(cmd, batch, phase, count);
        } else {
            buildGeoAddrTable(batch, phase, count);
        }

        int groupCount = (cacheMaxQuads + 15) / 16;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer init = stack.malloc(DRAW_COMMAND_BYTES);
            init.putInt(0, groupCount).putInt(4, 1).putInt(8, 1).putInt(12, 0);
            VK12.vkCmdUpdateBuffer(cmd, drawCommand[phase].vkBuffer(), 0L, init);
        }

        VkComputePipeline emit = pipelines.translucentEmitPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, emit.handle());
        long pyramidSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
        writer.storage(0, regionInput[phase]);
        writer.storage(1, manager.registry.translucentSectionDataVkBuffer(), 0L, manager.registry.translucentSectionDataByteCapacity());
        writer.storage(7, compactSections[phase]);
        writer.storage(8, command[phase]);
        writer.storage(14, liveMask[phase]);
        writer.uniform(5, manager.hizUboVk(), 0L, manager.hizUboBytes());
        writer.sampler(10, manager.terrainPyramidView(), pyramidSampler);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, emit.layout());
        VK12.vkCmdDispatch(cmd, count, 1, 1);

        VkMeshUtil.gatherInputBarrier(cmd);

        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.lightmap();
        long lightmapSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).vkSampler();
        VkComputePipeline gather = pipelines.translucentGatherPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, gather.handle());
        writer.storage(0, regionInput[phase]);
        writer.storage(1, manager.registry.translucentSectionDataVkBuffer(), 0L, manager.registry.translucentSectionDataByteCapacity());
        writer.storage(2, compactSections[phase]);
        writer.storage(3, geoAddrTable[phase]);
        writer.storage(4, manager.registry.translucentVisVkBuffer(), 0L, manager.registry.translucentVisByteSize());
        writer.uniform(5, manager.hizUboVk(), 0L, manager.hizUboBytes());
        writer.storage(8, command[phase]);
        writer.sampler(12, ((VulkanGpuTextureView) lightmapView).vkImageView(), lightmapSampler);
        writer.storage(16, cache[phase]);
        writer.storage(17, quadFade[phase]);
        writer.storage(18, drawCommand[phase]);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, gather.layout());
        VK12.vkCmdDispatch(cmd, count, 1, 1);

        VkMeshUtil.emitBarrier(cmd);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    @Override
    public void draw(VkTerrainDrawManager manager, OitMode mode, VkCommandBuffer cmd, OitFramebuffer framebuffer,
            boolean fading, GpuTextureView lightmapView, GpuTextureView blueNoiseView, GpuSampler clampLinear,
            GpuSampler oitSampler, GpuSampler noiseSampler, boolean localRead) {
        if (fading || count == 0 || cacheMaxQuads == 0) {
            return; // single stream: all translucent rides the settled (fading=false) call
        }
        Minecraft mc = Minecraft.getInstance();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh/translucent_oit/" + mode.name);
        VkMeshPipeline pipeline = pipelines.translucentDrawPipeline(mode, VK12.VK_FORMAT_D32_SFLOAT, localRead);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true)).vkSampler();
        GpuBufferSlice proj = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();

        // Pull the decoded cache (the gather already decoded/folded/culled); lightmap is baked in, so no b12 here.
        writer.storage(2, cache[phase]);
        writer.storage(3, quadFade[phase]);
        writer.storage(4, drawCommand[phase]);
        writer.sampler(10, atlasView, atlasSampler);
        writer.uniform(16, ((VulkanGpuBuffer) proj.buffer()).vkBuffer(), proj.offset(), proj.length());
        writer.uniform(18, ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
        writer.uniform(20, ((VulkanGpuBuffer) globals).vkBuffer(), 0L, globals.size());
        writer.uniform(21, modelViewUbo[phase].vkBuffer(), 0L, modelViewUbo[phase].sizeBytes());
        if (mode != OitMode.DEPTH_RANGE) {
            long depthRangeView = ((VulkanGpuTextureView) framebuffer.depthBoundsView()).vkImageView();
            if (localRead) {
                writer.inputAttachment(14, depthRangeView);
            } else {
                writer.sampler(14, depthRangeView, ((VulkanGpuSampler) oitSampler).vkSampler());
            }
            writer.sampler(15, ((VulkanGpuTextureView) blueNoiseView).vkImageView(), ((VulkanGpuSampler) noiseSampler).vkSampler());
        }
        if (mode == OitMode.EVALUATE) {
            long oitSamplerVk = ((VulkanGpuSampler) oitSampler).vkSampler();
            for (int i = 0; i < 4; i++) {
                long coeffView = ((VulkanGpuTextureView) framebuffer.coefficientsView(i)).vkImageView();
                if (localRead) {
                    writer.inputAttachment(24 + i, coeffView);
                } else {
                    writer.sampler(24 + i, coeffView, oitSamplerVk);
                }
            }
        }
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, drawCommand[phase].vkBuffer(), 0L, 1, DRAW_COMMAND_BYTES);
        VkContext.popLabel(cmd);
    }

    @Override
    public void drawMlab(VkTerrainDrawManager manager, OitInsertMode oitMode, VkCommandBuffer cmd, boolean fading,
            VkMlabBuffers mlab, GpuTextureView lightmapView, GpuSampler clampLinear) {
        if (fading || count == 0 || cacheMaxQuads == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh/translucent_mlab/" + oitMode);
        VkMeshPipeline pipeline = pipelines.translucentMlabPipeline(oitMode, VK12.VK_FORMAT_D32_SFLOAT);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true)).vkSampler();
        GpuBufferSlice proj = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();

        writer.storage(2, cache[phase]);
        writer.storage(3, quadFade[phase]);
        writer.storage(4, drawCommand[phase]);
        writer.sampler(10, atlasView, atlasSampler);
        writer.uniform(16, ((VulkanGpuBuffer) proj.buffer()).vkBuffer(), proj.offset(), proj.length());
        writer.uniform(18, ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
        writer.uniform(20, ((VulkanGpuBuffer) globals).vkBuffer(), 0L, globals.size());
        writer.uniform(21, modelViewUbo[phase].vkBuffer(), 0L, modelViewUbo[phase].sizeBytes());
        mlab.bind(writer);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, drawCommand[phase].vkBuffer(), 0L, 1, DRAW_COMMAND_BYTES);
        VkContext.popLabel(cmd);
    }

    private void packRegionInput(VisibleRegionBatch batch, int p, int n) {
        long ptr = regionInput[p].mappedAddress();
        for (int i = 0; i < n; i++) {
            long dst = ptr + (long) i * REGION_INPUT_STRIDE;
            MemoryUtil.memPutInt(dst, (batch.originChunkX[i] & 0xFFFF) | ((batch.originChunkZ[i] & 0xFFFF) << 16));
            MemoryUtil.memPutInt(dst + 4L, batch.originChunkY[i] & 0xFFFF);
            MemoryUtil.memPutInt(dst + 8L, batch.regionIds[i]);
            MemoryUtil.memPutInt(dst + 12L, 0);
        }
    }

    private void buildGeoAddrTable(VisibleRegionBatch batch, int p, int n) {
        geoAddrCache.clear();
        VkMeshUtil.writeGeoAddrTable(geoAddrTable[p].mappedAddress(), batch, n, geoAddrCache);
    }

    private void buildGeoAddrTableOwned(VkCommandBuffer cmd, VisibleRegionBatch batch, int p, int n) {
        geoAddrCache.clear();
        VkMeshUtil.writeGeoAddrTableOwned(cmd, geoAddrTable[p].mappedAddress(), batch, n, geoAddrCache, arena, pipelines);
    }

    private void ensureBuffers(int p, int n) {
        if (regionInput[p] == null) {
            regionInput[p] = new VkBuffer(STORAGE, (long) n * REGION_INPUT_STRIDE);
            command[p] = new VkBuffer(STORAGE | INDIRECT, (long) n * COMMAND_STRIDE);
            compactSections[p] = new VkBuffer(STORAGE, (long) n * VkMeshUtil.REGION_SIZE * Integer.BYTES);
            geoAddrTable[p] = new VkBuffer(STORAGE, (long) n * VkMeshUtil.GEO_ENTRY_BYTES);
            liveMask[p] = new VkBuffer(STORAGE, (long) n * LIVE_MASK_SLOT_BYTES);
            modelViewUbo[p] = new VkBuffer(UNIFORM, 64L);
        } else {
            regionInput[p].ensureCapacity((long) n * REGION_INPUT_STRIDE);
            command[p].ensureCapacity((long) n * COMMAND_STRIDE);
            compactSections[p].ensureCapacity((long) n * VkMeshUtil.REGION_SIZE * Integer.BYTES);
            geoAddrTable[p].ensureCapacity((long) n * VkMeshUtil.GEO_ENTRY_BYTES);
            liveMask[p].ensureCapacity((long) n * LIVE_MASK_SLOT_BYTES);
        }
    }

    private void ensureCacheBuffers(int p, int maxQuads) {
        long cacheBytes = (long) maxQuads * 4L * CACHED_VERT_BYTES;
        long fadeBytes = (long) maxQuads * Integer.BYTES;
        if (cache[p] == null) {
            cache[p] = new VkBuffer(STORAGE, cacheBytes, true);
            quadFade[p] = new VkBuffer(STORAGE, fadeBytes, true);
            drawCommand[p] = new VkBuffer(STORAGE | INDIRECT | TRANSFER_DST, DRAW_COMMAND_BYTES, true);
        } else {
            cache[p].ensureCapacity(cacheBytes);
            quadFade[p].ensureCapacity(fadeBytes);
        }
    }

    public void destroy() {
        for (int p = 0; p < 2; p++) {
            if (regionInput[p] != null) {
                regionInput[p].delete();
            }
            if (command[p] != null) {
                command[p].delete();
            }
            if (compactSections[p] != null) {
                compactSections[p].delete();
            }
            if (geoAddrTable[p] != null) {
                geoAddrTable[p].delete();
            }
            if (liveMask[p] != null) {
                liveMask[p].delete();
            }
            if (modelViewUbo[p] != null) {
                modelViewUbo[p].delete();
            }
            if (cache[p] != null) {
                cache[p].delete();
            }
            if (quadFade[p] != null) {
                quadFade[p].delete();
            }
            if (drawCommand[p] != null) {
                drawCommand[p].delete();
            }
        }
    }
}
