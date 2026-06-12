package dev.engine_room.flywheel.backend.gl.shader;

import dev.engine_room.flywheel.backend.FlwBackend;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

public final class MeshGlPrograms {
    private MeshGlPrograms() {
    }

    public static int compileShader(String tag, int glType, String name, String source) {
        int handle = GL20C.glCreateShader(glType);
        GL20C.glShaderSource(handle, source);
        GL20C.glCompileShader(handle);
        if (GL20C.glGetShaderi(handle, GL20C.GL_COMPILE_STATUS) != GL11C.GL_TRUE) {
            FlwBackend.LOGGER.error("[{}] {} compile failed:\n{}", tag, name, GL20C.glGetShaderInfoLog(handle));
            GL20C.glDeleteShader(handle);
            return 0;
        }
        return handle;
    }

    public static int linkProgram(String tag, String name, int... shaders) {
        int program = GL20C.glCreateProgram();
        for (int shader : shaders) {
            GL20C.glAttachShader(program, shader);
        }
        GL20C.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
            FlwBackend.LOGGER.error("[{}] {} link failed:\n{}", tag, name, GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
