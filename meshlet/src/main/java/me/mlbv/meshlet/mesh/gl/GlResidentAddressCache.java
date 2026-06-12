// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;

final class GlResidentAddressCache {
    private final Reference2LongOpenHashMap<GpuBuffer> addresses = new Reference2LongOpenHashMap<>();
    private boolean fetched;

    long address(GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return 0L;
        }
        long addr = addresses.getLong(buffer); // 0 = absent (a device address is never 0)
        if (addr == 0L) {
            int handle = GlMeshUtil.gpuBufferHandle(buffer);
            if (handle <= 0) {
                return 0L;
            }
            addr = GlMeshUtil.fetchResidentAddress(handle);
            fetched = true;
            addresses.put(buffer, addr);
        }
        return addr;
    }

    void finishFill() {
        if (fetched) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            fetched = false;
        }
        for (var it = addresses.reference2LongEntrySet().fastIterator(); it.hasNext(); ) {
            if (it.next().getKey().isClosed()) {
                it.remove();
            }
        }
    }

    void clear() {
        addresses.clear();
    }
}
