// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.vk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher.VisibleRegionBatch;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

// Pure-static glue shared by the opaque + translucent VK mesh rasterizers (relocation only, no behavioural change).
// Author-original 26.2-RHI adaptation, not Nvidium expression -> MIT, no derived-work header.
final class VkMeshUtil {
    static final int GEO_ENTRY_BYTES = Long.BYTES;
    static final int REGION_SIZE = 256;

    private VkMeshUtil() {
    }

    static long deviceAddress(long vkBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(vkBuffer);
            return VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), info);
        }
    }

    static void writeGeoAddrTable(long ptr, VisibleRegionBatch batch, int n, Long2LongOpenHashMap cache) {
        for (int slot = 0; slot < n; slot++) {
            GpuBuffer geo = batch.geometryBuffers[slot];
            long addr = 0L;
            if (geo != null && !geo.isClosed()) {
                long vk = ((VulkanGpuBuffer) geo).vkBuffer();
                addr = cache.get(vk);
                if (addr == 0L) {
                    addr = deviceAddress(vk);
                    cache.put(vk, addr);
                }
            }
            MemoryUtil.memPutLong(ptr + (long) slot * GEO_ENTRY_BYTES, addr);
        }
    }

    static void writeGeoAddrTableOwned(VkCommandBuffer cmd, long ptr, VisibleRegionBatch batch, int n,
            Long2LongOpenHashMap cache, VkMeshGeometryArena arena, VkMeshPipelines pipelines) {
        boolean gatherBound = false;
        for (int slot = 0; slot < n; slot++) {
            GpuBuffer geo = batch.geometryBuffers[slot];
            int regionId = batch.regionIds[slot];
            long ownedAddr = 0L;
            if (geo != null && !geo.isClosed()) {
                long vk = ((VulkanGpuBuffer) geo).vkBuffer();
                long sodiumAddr = cache.get(vk);
                if (sodiumAddr == 0L) {
                    sodiumAddr = deviceAddress(vk);
                    cache.put(vk, sodiumAddr);
                }
                long usedBytes = arena.usedBytes(regionId, geo.size());
                if (arena.needsGather(regionId, usedBytes)) {
                    if (!gatherBound) {
                        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines.gatherPipeline().handle());
                        gatherBound = true;
                    }
                    arena.recordGather(cmd, regionId, sodiumAddr, usedBytes);
                }
                ownedAddr = arena.address(regionId);
            }
            MemoryUtil.memPutLong(ptr + (long) slot * GEO_ENTRY_BYTES, ownedAddr);
        }
    }

    static void gatherInputBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, b, null, null);
        }
    }

    static void emitBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                            | EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT
                            | EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT,
                    0, b, null, null);
        }
    }
}
