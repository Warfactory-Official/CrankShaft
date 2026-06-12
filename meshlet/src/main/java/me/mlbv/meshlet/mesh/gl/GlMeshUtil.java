// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.NVShaderBufferLoad;

final class GlMeshUtil {
    private GlMeshUtil() {
    }

    static int gpuBufferHandle(GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return -1;
        }
        if (!(buffer instanceof GlBuffer glBuffer)) {
            return -1;
        }
        return glBuffer.handle();
    }

    static void bindTexture(int unit, GpuTextureView view, int samplerObj) {
        if (view == null || !(view.texture() instanceof GlTexture glTexture)) {
            return;
        }
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(glTexture.glId());
        GL33C.glBindSampler(unit, samplerObj);
        GlTextureLevelState.applyMipLevels(glTexture, view.baseMipLevel(), view.baseMipLevel() + view.mipLevels() - 1);
    }

    static long fetchResidentAddress(int handle) {
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, handle);
        if (!NVShaderBufferLoad.glIsBufferResidentNV(GL31C.GL_COPY_READ_BUFFER)) {
            NVShaderBufferLoad.glMakeBufferResidentNV(GL31C.GL_COPY_READ_BUFFER, GL15C.GL_READ_ONLY);
        }
        return NVShaderBufferLoad.glGetBufferParameterui64NV(GL31C.GL_COPY_READ_BUFFER,
                NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
    }

    static void uploadGeometryPointers(GpuBuffer[] geometryBuffers, int regionCount, GlGeometryPtrBuffer ptrs,
            GlResidentAddressCache residentAddresses) {
        ByteBuffer scratch = ptrs.ensureCapacity(regionCount);
        for (int slot = 0; slot < regionCount; slot++) {
            scratch.putLong(slot * Long.BYTES, residentAddresses.address(geometryBuffers[slot]));
        }
        residentAddresses.finishFill();
        ptrs.flush(regionCount);
    }

    static void uploadOwnedGeometryPointers(int[] regionIds, GpuBuffer[] geometryBuffers, int regionCount,
            GlGeometryPtrBuffer ptrs, GlResidentAddressCache residentAddresses, GlMeshGeometryArena arena) {
        ByteBuffer scratch = ptrs.ensureCapacity(regionCount);
        for (int slot = 0; slot < regionCount; slot++) {
            long addr = arena.address(regionIds[slot]);
            if (addr == 0L) {
                addr = residentAddresses.address(geometryBuffers[slot]);
            }
            scratch.putLong(slot * Long.BYTES, addr);
        }
        residentAddresses.finishFill();
        ptrs.flush(regionCount);
    }

    static void gatherOwnedGeometry(int[] regionIds, GpuBuffer[] geometryBuffers, int regionCount, int gatherProg,
            GlMeshGeometryArena arena) {
        if (gatherProg == 0) {
            return;
        }
        boolean bound = false;
        for (int slot = 0; slot < regionCount; slot++) {
            GpuBuffer geo = geometryBuffers[slot];
            int handle = gpuBufferHandle(geo);
            if (handle <= 0) {
                continue;
            }
            int regionId = regionIds[slot];
            long usedBytes = arena.usedBytes(regionId, geo.size());
            if (arena.needsGather(regionId, usedBytes)) {
                if (!bound) {
                    GlStateTracker.useProgram(gatherProg);
                    bound = true;
                }
                arena.recordGather(regionId, handle, usedBytes);
            }
        }
    }
}
