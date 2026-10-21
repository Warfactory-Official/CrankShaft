package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

final class DeferredReplayQuad {
    private final GlVertexArray vao = GlVertexArray.create();
    private final GlBuffer vertices = new GlBuffer(GlBufferUsage.STATIC_DRAW);
    private final GlBuffer indices = new GlBuffer(GlBufferUsage.STATIC_DRAW);

    DeferredReplayQuad() {
        var vertexData = MemoryBlock.malloc(80);
        var indexData = MemoryBlock.malloc(24);
        try {
            var corners = new float[]{0, 0, 1, 0, 1, 1, 0, 1};
            for (var index = 0; index < 4; index++) {
                var ptr = vertexData.ptr() + index * 20L;
                MemoryUtil.memPutFloat(ptr, corners[index * 2]);
                MemoryUtil.memPutFloat(ptr + 4, corners[index * 2 + 1]);
                MemoryUtil.memPutFloat(ptr + 8, 0);
                MemoryUtil.memPutFloat(ptr + 12, corners[index * 2]);
                MemoryUtil.memPutFloat(ptr + 16, corners[index * 2 + 1]);
            }
            var order = new int[]{0, 1, 2, 0, 2, 3};
            for (var index = 0; index < 6; index++) MemoryUtil.memPutInt(indexData.ptr() + index * 4L, order[index]);
            vertices.upload(vertexData);
            indices.upload(indexData);
        } finally {
            vertexData.free();
            indexData.free();
        }
        vao.bindVertexBuffer(0, vertices.handle(), 0, 20);
        GL45.glVertexArrayAttribFormat(vao.handle(), 0, 3, GL11.GL_FLOAT, false, 0);
        GL45.glVertexArrayAttribBinding(vao.handle(), 0, 0);
        GL45.glEnableVertexArrayAttrib(vao.handle(), 0);
        // Compatibility composite shaders read gl_MultiTexCoord0 at the conventional attribute alias.
        GL45.glVertexArrayAttribFormat(vao.handle(), 8, 2, GL11.GL_FLOAT, false, 12);
        GL45.glVertexArrayAttribBinding(vao.handle(), 8, 0);
        GL45.glEnableVertexArrayAttrib(vao.handle(), 8);
        vao.setElementBuffer(indices.handle());
    }

    void bind() {
        vao.bindForDraw();
    }

    void delete() {
        vao.delete();
        vertices.delete();
        indices.delete();
    }
}
