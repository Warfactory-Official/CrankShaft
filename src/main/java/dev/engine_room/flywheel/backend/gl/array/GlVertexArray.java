package dev.engine_room.flywheel.backend.gl.array;

import dev.engine_room.flywheel.backend.gl.GlObject;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.List;

public abstract class GlVertexArray extends GlObject {
    protected static final int MAX_ATTRIBS = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
    protected static final int MAX_ATTRIB_BINDINGS = 16;

    public static GlVertexArray create() {
        if (GlVertexArrayDSA.SUPPORTED) {
            return new GlVertexArrayDSA();
        } else if (GlVertexArraySeparateAttributes.SUPPORTED) {
            return new GlVertexArraySeparateAttributes();
        } else if (GlVertexArrayGL3.Core33.SUPPORTED) {
            return new GlVertexArrayGL3.Core33();
        } else if (GlVertexArrayGL3.ARB.SUPPORTED) {
            return new GlVertexArrayGL3.ARB();
        } else {
            return new GlVertexArrayGL3.Core();
        }
    }

    public void bindForDraw() {
        GlStateTracker.bindVao(handle());
    }

    public abstract void bindVertexBuffer(int bindingIndex, int vbo, long offset, int stride);

    public abstract void setBindingDivisor(int bindingIndex, int divisor);

    public abstract void bindAttributes(int bindingIndex, int startAttribIndex, List<VertexAttribute> vertexAttributes);

    public abstract void setElementBuffer(int ebo);

    @Override
    protected void deleteInternal(int handle) {
        GL30.glDeleteVertexArrays(handle);
    }
}
