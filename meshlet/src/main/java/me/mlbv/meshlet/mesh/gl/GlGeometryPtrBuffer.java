// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

final class GlGeometryPtrBuffer {
    private int buffer = 0;
    private int capacityRegions = 0;
    private ByteBuffer scratch = null;

    ByteBuffer ensureCapacity(int regionCount) {
        if (buffer == 0 || regionCount > capacityRegions) {
            int newCap = Math.max(regionCount, capacityRegions * 2);
            if (buffer == 0) {
                buffer = GL15C.glGenBuffers();
            }
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAlloc(newCap * Long.BYTES);
            capacityRegions = newCap;
        }
        return scratch;
    }

    void flush(int regionCount) {
        long bytes = (long) regionCount * Long.BYTES;
        scratch.position(0).limit((int) bytes);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_STREAM_DRAW);
        GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, scratch);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        scratch.clear();
    }

    void bindBase(int binding) {
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    void destroy() {
        if (buffer != 0) {
            GL15C.glDeleteBuffers(buffer);
            buffer = 0;
            capacityRegions = 0;
        }
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }
    }
}
