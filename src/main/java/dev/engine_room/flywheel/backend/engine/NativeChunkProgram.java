package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.engine.shaderpack.GuestPrograms;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslPreprocessor;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;

final class NativeChunkProgram {
    private final GlProgram program;
    private final NativeUniform[] uniforms;
    private boolean validated;

    private NativeChunkProgram(GlProgram program, NativeUniform[] uniforms) {
        this.program = program;
        this.uniforms = uniforms;
    }

    void bind(int original) {
        program.bind();
        for (var uniform : uniforms) uniform.copy(original);
        if (!validated) {
            GL20.glValidateProgram(program.handle());
            if (GL20.glGetProgrami(program.handle(), GL20.GL_VALIDATE_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("Native terrain OIT validation: " + GL20.glGetProgramInfoLog(program.handle()));
            validated = true;
        }
    }

    void delete() { program.delete(); }

    static NativeChunkProgram create(int original, PipelineCompiler.OitMode mode) {
        if (Boolean.getBoolean("flywheel.nativeChunkOitDebug"))
            FlwBackend.LOGGER.info("Native terrain OIT compile: program={}, phase={}", original, mode);
        if (original == 0) throw new UnsupportedOperationException("Native terrain OIT requires a programmable renderer");
        if (GL20.glGetProgrami(original, GL31.GL_ACTIVE_UNIFORM_BLOCKS) != 0)
            throw new UnsupportedOperationException("Native terrain uniform blocks need an explicit binding contract");
        var vertex = shader(original, GL20.GL_VERTEX_SHADER);
        var fragment = shader(original, GL20.GL_FRAGMENT_SHADER);
        var source = NativeChunkShader.transform(
                GlslPreprocessor.preprocess(GL20.glGetShaderSource(vertex)),
                GlslPreprocessor.preprocess(GL20.glGetShaderSource(fragment)),
                GlslPreprocessor.preprocess(GL20.glGetShaderSource(shader(
                        FlwPrograms.chunkOitPrograms().get(mode).handle(), GL20.GL_FRAGMENT_SHADER))),
                GlCompat.MAX_GLSL_VERSION.version);
        var compiled = GuestPrograms.compile(GL20.GL_FRAGMENT_SHADER, source);
        var handle = GL20.glCreateProgram();
        try {
            GL20.glAttachShader(handle, vertex);
            GL20.glAttachShader(handle, compiled);
            try (var stack = MemoryStack.stackPush()) {
                var size = stack.mallocInt(1);
                var type = stack.mallocInt(1);
                for (var index = 0; index < GL20.glGetProgrami(original, GL20.GL_ACTIVE_ATTRIBUTES); index++) {
                    var name = GL20.glGetActiveAttrib(original, index, GL20.glGetProgrami(original, GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH), size, type);
                    if (!name.startsWith("gl_")) GL20.glBindAttribLocation(handle, GL20.glGetAttribLocation(original, name), name);
                }
            }
            GL30.glBindFragDataLocation(handle, 0, "_flw_depthRange_out");
            GL30.glBindFragDataLocation(handle, 0, "_flw_accumulate");
            for (var index = 0; index < 4; index++) GL30.glBindFragDataLocation(handle, index, "_flw_coeffs" + index);
            GL20.glLinkProgram(handle);
            if (GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("Native terrain OIT link: " + GL20.glGetProgramInfoLog(handle));
            var program = new GlProgram(handle);
            program.bind();
            Uniforms.setUniformBlockBindings(program);
            program.setSamplerBinding("_flw_depthRange", Samplers.DEPTH_RANGE);
            program.setSamplerBinding("_flw_coefficients", Samplers.COEFFICIENTS);
            program.setSamplerBinding("_flw_blueNoise", Samplers.NOISE);
            return new NativeChunkProgram(program, uniforms(original, handle));
        } catch (RuntimeException | Error error) {
            GL20.glDeleteProgram(handle);
            throw error;
        } finally { GL20.glDeleteShader(compiled); }
    }

    private static int shader(int program, int type) {
        try (var stack = MemoryStack.stackPush()) {
            var shaders = stack.mallocInt(GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS));
            GL20.glGetAttachedShaders(program, null, shaders);
            while (shaders.hasRemaining()) {
                var id = shaders.get();
                if (GL20.glGetShaderi(id, GL20.GL_SHADER_TYPE) == type) return id;
            }
        }
        throw new IllegalStateException("Native chunk program lost its shader source");
    }

    private static NativeUniform[] uniforms(int original, int target) {
        var uniforms = new ArrayList<NativeUniform>();
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            for (var index = 0; index < GL20.glGetProgrami(original, GL20.GL_ACTIVE_UNIFORMS); index++) {
                var name = GL20.glGetActiveUniform(original, index, GL20.glGetProgrami(original, GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH), size, type);
                var count = size.get(0);
                if (Boolean.getBoolean("flywheel.nativeChunkOitDebug"))
                    FlwBackend.LOGGER.info("Native terrain uniform: name={}, type={}, count={}", name, type.get(0), count);
                var base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
                for (var element = 0; element < count; element++) {
                    var key = count == 1 ? name : base + "[" + element + "]";
                    var to = GL20.glGetUniformLocation(target, key);
                    if (to >= 0) uniforms.add(new NativeUniform(GL20.glGetUniformLocation(original, key), to, type.get(0)));
                }
            }
        }
        return uniforms.toArray(NativeUniform[]::new);
    }

    private record NativeUniform(int from, int to, int type) {
        void copy(int original) {
            try (var stack = MemoryStack.stackPush()) {
                var values = stack.mallocFloat(16);
                switch (type) {
                    case GL20.GL_FLOAT -> {
                        GL20.glGetUniformfv(original, from, values);
                        GL20.glUniform1f(to, values.get(0));
                    }
                    case GL20.GL_FLOAT_VEC2 -> {
                        GL20.glGetUniformfv(original, from, values);
                        GL20.glUniform2f(to, values.get(0), values.get(1));
                    }
                    case GL20.GL_FLOAT_VEC3 -> {
                        GL20.glGetUniformfv(original, from, values);
                        GL20.glUniform3f(to, values.get(0), values.get(1), values.get(2));
                    }
                    case GL20.GL_FLOAT_VEC4 -> {
                        GL20.glGetUniformfv(original, from, values);
                        GL20.glUniform4f(to, values.get(0), values.get(1), values.get(2), values.get(3));
                    }
                    case GL20.GL_FLOAT_MAT4 -> {
                        GL20.glGetUniformfv(original, from, values);
                        GL20.glUniformMatrix4fv(to, false, values);
                    }
                    case GL20.GL_FLOAT_MAT3 -> {
                        GL20.glGetUniformfv(original, from, values);
                        values.limit(9);
                        GL20.glUniformMatrix3fv(to, false, values);
                    }
                    case GL20.GL_FLOAT_MAT2 -> {
                        GL20.glGetUniformfv(original, from, values);
                        values.limit(4);
                        GL20.glUniformMatrix2fv(to, false, values);
                    }
                    default -> {
                        var ints = stack.mallocInt(4);
                        GL20.glGetUniformiv(original, from, ints);
                        switch (type) {
                            case GL20.GL_INT_VEC2, GL20.GL_BOOL_VEC2 -> GL20.glUniform2i(to, ints.get(0), ints.get(1));
                            case GL20.GL_INT_VEC3, GL20.GL_BOOL_VEC3 -> GL20.glUniform3i(to, ints.get(0), ints.get(1), ints.get(2));
                            case GL20.GL_INT_VEC4, GL20.GL_BOOL_VEC4 -> GL20.glUniform4i(to, ints.get(0), ints.get(1), ints.get(2), ints.get(3));
                            case GL20.GL_INT, GL20.GL_BOOL, GL20.GL_SAMPLER_2D -> GL20.glUniform1i(to, ints.get(0));
                            default -> throw new UnsupportedOperationException("Native terrain uniform type: " + type);
                        }
                    }
                }
            }
        }
    }

}
