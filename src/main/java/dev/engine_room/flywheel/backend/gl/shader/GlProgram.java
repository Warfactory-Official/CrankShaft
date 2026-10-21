package dev.engine_room.flywheel.backend.gl.shader;

import dev.engine_room.flywheel.backend.gl.GlObject;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

import static org.lwjgl.opengl.GL31.*;

public class GlProgram extends GlObject {
    // Mirrors upstream's `LogUtils.getLogger()` — class-FQN logger, deliberately not under FlwBackend.
    private static final Logger LOGGER = LogManager.getLogger(GlProgram.class);

    private final Object2IntMap<String> uniformLocationCache = new Object2IntOpenHashMap<>();

    public GlProgram(int handle) {
        handle(handle);
        uniformLocationCache.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static void bind(int h) {
        if (h != GlStateTracker.getProgram()) {
            glUseProgram(h);
            GlStateTracker._setProgram(h);
        }
    }

    public static void unbind() {
        bind(0);
    }

    public void bind() {
        bind(handle());
    }

    public void setFloat(String glslName, float value) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform1f(uniform, value);
    }

    public void setVec2(String glslName, float x, float y) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform2f(uniform, x, y);
    }

    public void setVec3(String glslName, float x, float y, float z) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform3f(uniform, x, y, z);
    }

    public void setVec4(String glslName, float x, float y, float z, float w) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform4f(uniform, x, y, z, w);
    }

    public void setMat4(String glslName, Matrix4fc matrix) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniformMatrix4fv(uniform, false, matrix.get(new float[16]));
    }

    public void setMat3(String glslName, Matrix3fc matrix) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniformMatrix3fv(uniform, false, matrix.get(new float[9]));
    }

    public void setBool(String glslName, boolean bool) {
        setInt(glslName, bool ? 1 : 0);
    }

    public void setUInt(String glslName, int value) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform1ui(uniform, value);
    }

    public void setUVec2(String name, int x, int y) {
        int uniform = getUniformLocation(name);

        if (uniform < 0) {
            return;
        }

        glUniform2ui(uniform, x, y);
    }

    public void setInt(String glslName, int value) {
        int uniform = getUniformLocation(glslName);

        if (uniform < 0) {
            return;
        }

        glUniform1i(uniform, value);
    }

    public int getUniformLocation(String uniform) {
        var index = uniformLocationCache.getInt(uniform);
        if (index != Integer.MIN_VALUE) return index;
        index = glGetUniformLocation(this.handle(), uniform);
        if (index < 0) {
            LOGGER.debug("No active uniform '{}' exists. Could be unused.", uniform);
        }
        uniformLocationCache.put(uniform, index);
        return index;
    }

    public void setSamplerBinding(String name, GlTextureUnit binding) {
        setSamplerBinding(name, binding.number);
    }

    public void setSamplerBinding(String name, int binding) {
        int samplerUniform = getUniformLocation(name);

        if (samplerUniform >= 0) {
            glUniform1i(samplerUniform, binding);
        }
    }

    public void setUniformBlockBinding(String name, int binding) {
        int index = glGetUniformBlockIndex(handle(), name);

        if (index == GL_INVALID_INDEX) {
            LOGGER.debug("No active uniform block '{}' exists. Could be unused.", name);
            return;
        }

        glUniformBlockBinding(handle(), index, binding);
    }

    public void bindAttribLocation(String attribute, int binding) {
        glBindAttribLocation(handle(), binding, attribute);
    }

    @Override
    protected void deleteInternal(int handle) {
        glDeleteProgram(handle);
    }
}
