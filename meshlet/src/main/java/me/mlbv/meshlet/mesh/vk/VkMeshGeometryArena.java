// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the VK_EXT_mesh_shader port + CrankShaft integration)
// Derivative work of Nvidium's owned-geometry model (a mod-owned CompactChunkVertex arena, not an alias of Sodium's).

package me.mlbv.meshlet.mesh.vk;

import dev.engine_room.flywheel.backend.engine.terrain.OwnedGeometryListener;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionRegistry;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;

import dev.engine_room.flywheel.backend.FlwBackend;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class VkMeshGeometryArena implements OwnedGeometryListener {
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int TRANSFER_DST = VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int SHADER_DEVICE_ADDRESS = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int OWNED_USAGE = STORAGE | TRANSFER_DST | SHADER_DEVICE_ADDRESS;
    private static final int GATHER_LOCAL_SIZE = 64;

    private final VkMeshPipelines pipelines;
    private final Int2ObjectMap<VkBuffer> buffers = new Int2ObjectOpenHashMap<>();
    private final IntSet dirty = new IntOpenHashSet();
    private boolean loggedActive;
    @Nullable
    private TerrainSectionRegistry registry;

    public VkMeshGeometryArena(VkMeshPipelines pipelines) {
        this.pipelines = pipelines;
    }

    public void attach(TerrainSectionRegistry reg) {
        if (registry != reg) {
            registry = reg;
            reg.setOwnedGeometryListener(this);
        }
    }

    @Override
    public void onRegionDirty(int regionId) {
        dirty.add(regionId);
    }

    @Override
    public void onRegionFreed(int regionId) {
        VkBuffer buf = buffers.remove(regionId);
        if (buf != null) {
            buf.delete();
        }
        dirty.remove(regionId);
    }

    public long usedBytes(int regionId, long capacity) {
        int extent = registry == null ? 0 : registry.regionMaxVertexExtent(regionId);
        return extent > 0 ? Math.min((long) extent * MeshShaderPrep.VERTEX_STRIDE, capacity) : capacity;
    }

    public boolean needsGather(int regionId, long sodiumSize) {
        VkBuffer buf = buffers.get(regionId);
        return buf == null || buf.sizeBytes() != sodiumSize || dirty.contains(regionId);
    }

    public void recordGather(VkCommandBuffer cmd, int regionId, long sodiumAddr, long sodiumSize) {
        if (!loggedActive) {
            FlwBackend.LOGGER.info("[vk_mesh_shader] owned-geometry ACTIVE (meshlet.ownGeometry): copying Sodium arenas into owned buffers");
            loggedActive = true;
        }
        VkBuffer old = buffers.get(regionId);
        VkBuffer buf = new VkBuffer(OWNED_USAGE, sodiumSize, true);
        long wordCount = sodiumSize / Integer.BYTES;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pc = stack.nmalloc(VkGatherPipeline.PUSH_CONSTANT_BYTES);
            MemoryUtil.memPutLong(pc, sodiumAddr);
            MemoryUtil.memPutLong(pc + 8L, buf.deviceAddress());
            MemoryUtil.memPutInt(pc + 16L, (int) wordCount);
            VK12.nvkCmdPushConstants(cmd, pipelines.gatherPipeline().layout(), VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    VkGatherPipeline.PUSH_CONSTANT_BYTES, pc);
        }
        VK12.vkCmdDispatch(cmd, (int) ((wordCount + GATHER_LOCAL_SIZE - 1) / GATHER_LOCAL_SIZE), 1, 1);
        buffers.put(regionId, buf);
        if (old != null) {
            old.delete();
        }
        dirty.remove(regionId);
    }

    public long address(int regionId) {
        VkBuffer buf = buffers.get(regionId);
        return buf == null ? 0L : buf.deviceAddress();
    }

    public void destroy() {
        if (registry != null) {
            registry.setOwnedGeometryListener(null);
            registry = null;
        }
        for (VkBuffer buf : buffers.values()) {
            buf.delete();
        }
        buffers.clear();
        dirty.clear();
    }
}
