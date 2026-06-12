// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the GL_NV port + CrankShaft integration)
// Derivative work of Nvidium's owned-geometry model (a mod-owned CompactChunkVertex arena, not an alias of Sodium's).

package me.mlbv.meshlet.mesh.gl;

import java.util.ArrayDeque;

import dev.engine_room.flywheel.backend.engine.terrain.OwnedGeometryListener;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionRegistry;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;

import dev.engine_room.flywheel.backend.FlwBackend;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.NVShaderBufferLoad;

public final class GlMeshGeometryArena implements OwnedGeometryListener {
    private final GlMeshPipelines pipelines;
    private final Int2ObjectMap<Owned> buffers = new Int2ObjectOpenHashMap<>();
    private final IntSet dirty = new IntOpenHashSet();
    private final ArrayDeque<Retired> pendingDelete = new ArrayDeque<>();
    private boolean loggedActive;
    @Nullable
    private TerrainSectionRegistry registry;

    public GlMeshGeometryArena(GlMeshPipelines pipelines) {
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
        Owned owned = buffers.remove(regionId);
        if (owned != null) {
            retire(owned.handle, owned.size);
        }
        dirty.remove(regionId);
    }

    public long usedBytes(int regionId, long capacity) {
        int extent = registry == null ? 0 : registry.regionMaxVertexExtent(regionId);
        return extent > 0 ? Math.min((long) extent * MeshShaderPrep.VERTEX_STRIDE, capacity) : capacity;
    }

    public boolean needsGather(int regionId, long sodiumSize) {
        Owned owned = buffers.get(regionId);
        return owned == null || owned.size != sodiumSize || dirty.contains(regionId);
    }

    public void recordGather(int regionId, int sodiumHandle, long sodiumSize) {
        if (!loggedActive) {
            FlwBackend.LOGGER.info("[gl_mesh_shader] owned-geometry ACTIVE (meshlet.ownGeometry): copying Sodium arenas into owned buffers");
            loggedActive = true;
        }
        int handle = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(handle, sodiumSize, 0);
        FlwMemoryTracker._allocGpuMemory(sodiumSize);
        NVShaderBufferLoad.glMakeNamedBufferResidentNV(handle, GL15C.GL_READ_ONLY);
        long address = NVShaderBufferLoad.glGetNamedBufferParameterui64NV(handle, NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);

        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, sodiumHandle);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, handle);
        long words = sodiumSize / Integer.BYTES;
        GL43C.glDispatchCompute((int) ((words + 63L) / 64L), 1, 1);

        Owned old = buffers.put(regionId, new Owned(handle, address, sodiumSize));
        if (old != null) {
            retire(old.handle, old.size);
        }
        dirty.remove(regionId);
    }

    public long address(int regionId) {
        Owned owned = buffers.get(regionId);
        return owned == null ? 0L : owned.address;
    }

    public void tick() {
        while (!pendingDelete.isEmpty()) {
            Retired r = pendingDelete.peekFirst();
            int status = GL32C.glClientWaitSync(r.sync, 0, 0L);
            if (status == GL32C.GL_ALREADY_SIGNALED || status == GL32C.GL_CONDITION_SATISFIED) {
                GL32C.glDeleteSync(r.sync);
                GL15C.glDeleteBuffers(r.handle);
                FlwMemoryTracker._freeGpuMemory(r.size);
                pendingDelete.removeFirst();
            } else {
                break;
            }
        }
    }

    public void destroy() {
        if (registry != null) {
            registry.setOwnedGeometryListener(null);
            registry = null;
        }
        if (buffers.isEmpty() && pendingDelete.isEmpty()) {
            return;
        }
        GL11C.glFinish();
        for (Owned owned : buffers.values()) {
            GL15C.glDeleteBuffers(owned.handle);
            FlwMemoryTracker._freeGpuMemory(owned.size);
        }
        buffers.clear();
        dirty.clear();
        for (Retired r : pendingDelete) {
            GL32C.glDeleteSync(r.sync);
            GL15C.glDeleteBuffers(r.handle);
            FlwMemoryTracker._freeGpuMemory(r.size);
        }
        pendingDelete.clear();
    }

    private void retire(int handle, long size) {
        pendingDelete.addLast(new Retired(handle, GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0), size));
    }

    private record Owned(int handle, long address, long size) {
    }

    private record Retired(int handle, long sync, long size) {
    }
}
