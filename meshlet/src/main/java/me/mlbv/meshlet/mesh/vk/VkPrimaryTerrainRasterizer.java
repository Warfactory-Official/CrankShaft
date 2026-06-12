// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the VK_EXT_mesh_shader port + CrankShaft integration)
// Derivative work of Nvidium GlPrimaryTerrainRasterizer (the single-multidraw mesh-task scheme).

package me.mlbv.meshlet.mesh.vk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.VisibleRegionBatch;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkMeshPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainMeshDrawStrategy;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The opaque vk_mesh_shader terrain draw strategy: the VK twin of {@code GlPrimaryTerrainRasterizer}. Replaces the
 * MDI opaque draw with one {@code vkCmdDrawMeshTasksIndirectEXT} per pass; geometry is read by device address (BDA).
 */
public final class VkPrimaryTerrainRasterizer implements VkTerrainMeshDrawStrategy {
    private static final int PASS_COUNT = 2;
    private static final int COMMAND_STRIDE = 32; // VkDrawMeshTasksIndirectCommandEXT + the per-region record
    private static final int COLOR_FORMAT = VK12.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int DEPTH_FORMAT = VK12.VK_FORMAT_D32_SFLOAT;
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int INDIRECT = VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
    private final VkMeshPipelines pipelines;
    private final VkDescriptorWriter writer = new VkDescriptorWriter();

    private final VkBuffer[][] meshCommands = new VkBuffer[PASS_COUNT][2];
    private final VkBuffer[][] compactSections = new VkBuffer[PASS_COUNT][2];
    private final VkBuffer[][] geoAddrTable = new VkBuffer[PASS_COUNT][2];

    private final Long2LongOpenHashMap geoAddrCache = new Long2LongOpenHashMap();
    private int cacheParity = -1;

    @Nullable
    private VkMeshGeometryArena arena;

    public VkPrimaryTerrainRasterizer(VkMeshPipelines pipelines) {
        this.pipelines = pipelines;
        geoAddrCache.defaultReturnValue(0L);
    }

    public void setArena(@Nullable VkMeshGeometryArena arena) {
        this.arena = arena;
    }

    @Override
    public void prepareEmit(VkTerrainDrawManager manager, int pass) {
        VisibleRegionBatch batch = manager.boundBatch;
        int n = batch.count;
        if (n == 0) {
            return;
        }
        int parity = manager.boundParity;
        ensureBuffers(pass, parity, n);

        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh/emit/" + (pass == 0 ? "solid" : "cutout"));
        if (arena != null) {
            arena.attach(manager.registry);
            buildGeoAddrTableOwned(cmd, batch, pass, parity, n);
        } else {
            buildGeoAddrTable(batch, pass, parity, n);
        }
        VkComputePipeline emit = pipelines.emitPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, emit.handle());
        writer.storage(0, manager.regionInputVk(pass), 0L, manager.regionInputBytes(pass));
        writer.storage(1, manager.registry.sectionDataVkBuffer(pass), 0L, manager.registry.sectionDataByteCapacity(pass));
        writer.storage(2, manager.regionVisVk(pass), 0L, manager.regionVisBytes(pass));
        writer.storage(3, manager.registry.sectionVisVkBuffer(pass), 0L, manager.registry.sectionVisByteSize());
        writer.storage(4, manager.registry.translucentVisVkBuffer(), 0L, manager.registry.translucentVisByteSize());
        writer.uniform(5, manager.hizUboVk(), 0L, manager.hizUboBytes());
        writer.uniform(6, manager.regionCountUboVk(pass), 0L, manager.regionCountUboBytes(pass));
        writer.storage(7, compactSections[pass][parity]);
        writer.storage(8, meshCommands[pass][parity]);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, emit.layout());
        VK12.vkCmdDispatch(cmd, n, 1, 1);
        VkMeshUtil.emitBarrier(cmd);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    @Override
    public void drawOpaque(VkTerrainDrawManager manager, int pass, VkCommandBuffer cmd) {
        VisibleRegionBatch batch = manager.boundBatch;
        int n = batch.count;
        if (n == 0) {
            return;
        }
        int parity = manager.boundParity;
        Minecraft mc = Minecraft.getInstance();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/mesh/draw/" + (pass == 0 ? "solid" : "cutout"));
        VkMeshPipeline draw = pipelines.drawPipeline(pass != 0, COLOR_FORMAT, DEPTH_FORMAT);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, draw.handle());

        long atlasView = ((VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()).vkImageView();
        long atlasSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true)).vkSampler();
        long lightmapView = ((VulkanGpuTextureView) mc.gameRenderer.lightmap()).vkImageView();
        long lightmapSampler = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).vkSampler();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();

        writer.storage(0, manager.regionInputVk(pass), 0L, manager.regionInputBytes(pass));
        writer.storage(1, manager.registry.sectionDataVkBuffer(pass), 0L, manager.registry.sectionDataByteCapacity(pass));
        writer.storage(2, compactSections[pass][parity]);
        writer.storage(3, geoAddrTable[pass][parity]);
        writer.storage(4, manager.registry.translucentVisVkBuffer(), 0L, manager.registry.translucentVisByteSize());
        writer.uniform(5, manager.hizUboVk(), 0L, manager.hizUboBytes());
        writer.sampler(10, atlasView, atlasSampler);
        writer.sampler(12, lightmapView, lightmapSampler);
        writer.uniform(16, ((VulkanGpuBuffer) projection.buffer()).vkBuffer(), projection.offset(), projection.length());
        writer.uniform(18, ((VulkanGpuBuffer) fog.buffer()).vkBuffer(), fog.offset(), fog.length());
        writer.uniform(20, ((VulkanGpuBuffer) globals).vkBuffer(), 0L, globals.size());
        writer.uniform(21, manager.chunkSectionUboVk(), 0L, manager.chunkSectionUboSize());
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, draw.layout());

        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, meshCommands[pass][parity].vkBuffer(), 0L, n, COMMAND_STRIDE);
        VkContext.popLabel(cmd);
    }

    private void buildGeoAddrTable(VisibleRegionBatch batch, int pass, int parity, int n) {
        if (parity != cacheParity) {
            geoAddrCache.clear();
            cacheParity = parity;
        }
        VkMeshUtil.writeGeoAddrTable(geoAddrTable[pass][parity].mappedAddress(), batch, n, geoAddrCache);
    }

    private void buildGeoAddrTableOwned(VkCommandBuffer cmd, VisibleRegionBatch batch, int pass, int parity, int n) {
        if (parity != cacheParity) {
            geoAddrCache.clear();
            cacheParity = parity;
        }
        VkMeshUtil.writeGeoAddrTableOwned(cmd, geoAddrTable[pass][parity].mappedAddress(), batch, n,
                geoAddrCache, arena, pipelines);
    }

    private void ensureBuffers(int pass, int parity, int n) {
        if (meshCommands[pass][parity] == null) {
            meshCommands[pass][parity] = new VkBuffer(STORAGE | INDIRECT, (long) n * COMMAND_STRIDE);
            compactSections[pass][parity] = new VkBuffer(STORAGE, (long) n * VkMeshUtil.REGION_SIZE * Integer.BYTES);
            geoAddrTable[pass][parity] = new VkBuffer(STORAGE, (long) n * VkMeshUtil.GEO_ENTRY_BYTES);
        } else {
            meshCommands[pass][parity].ensureCapacity((long) n * COMMAND_STRIDE);
            compactSections[pass][parity].ensureCapacity((long) n * VkMeshUtil.REGION_SIZE * Integer.BYTES);
            geoAddrTable[pass][parity].ensureCapacity((long) n * VkMeshUtil.GEO_ENTRY_BYTES);
        }
    }

    public void destroy() {
        for (int p = 0; p < PASS_COUNT; p++) {
            for (int q = 0; q < 2; q++) {
                if (meshCommands[p][q] != null) {
                    meshCommands[p][q].delete();
                }
                if (compactSections[p][q] != null) {
                    compactSections[p][q].delete();
                }
                if (geoAddrTable[p][q] != null) {
                    geoAddrTable[p][q].delete();
                }
            }
        }
    }
}
