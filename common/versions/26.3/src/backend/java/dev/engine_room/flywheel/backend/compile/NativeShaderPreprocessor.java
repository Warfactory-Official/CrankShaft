package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves vanilla includes with the stage's defines in effect. Include memory belongs to this compilation;
 * neither shader assembly nor draw submission owns native include pointers. Works on GL and Vulkan hosts.
 */
public final class NativeShaderPreprocessor {
    // Resolve the loader call site on the Java caller before entering any native include callback.
    private static final Function<String, ShaderSource.CachedIncludeSource> INCLUDE_LOADER =
            NativeShaderPreprocessor::loadInclude;

    private NativeShaderPreprocessor() {
    }

    public static String preprocess(String source, String label, ShaderType type, ShaderDefines defines) {
        return preprocess(source, label, type == ShaderType.VERTEX ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER,
                defines);
    }

    public static String preprocess(String source, String label, int glShaderType, ShaderDefines defines) {
        int kind = switch (glShaderType) {
            case GL20C.GL_VERTEX_SHADER -> Shaderc.shaderc_vertex_shader;
            case GL20C.GL_FRAGMENT_SHADER -> Shaderc.shaderc_fragment_shader;
            case GL43C.GL_COMPUTE_SHADER -> Shaderc.shaderc_compute_shader;
            case GL32C.GL_GEOMETRY_SHADER -> Shaderc.shaderc_geometry_shader;
            case GL40C.GL_TESS_CONTROL_SHADER -> Shaderc.shaderc_tess_control_shader;
            case GL40C.GL_TESS_EVALUATION_SHADER -> Shaderc.shaderc_tess_evaluation_shader;
            case NVMeshShader.GL_TASK_SHADER_NV -> Shaderc.shaderc_task_shader;
            case NVMeshShader.GL_MESH_SHADER_NV -> Shaderc.shaderc_mesh_shader;
            default -> throw new IllegalArgumentException("Unknown native shader stage: " + glShaderType);
        };
        Map<String, String> macros = new HashMap<>(defines.values());
        defines.flags().forEach(flag -> macros.put(flag, ""));
        return preprocessKind(source, label, kind, macros);
    }

    public static String preprocessKind(String source, String label, int kind, Map<String, String> defines) {
        Map<String, ShaderSource.CachedIncludeSource> includes = new HashMap<>();
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0;
        ShadercIncludeResolve resolver =
                ShadercIncludeResolve.create((context, requested, includeType, requester, depth) -> {
            String name = MemoryUtil.memUTF8(requested);
            return includes.computeIfAbsent(name, INCLUDE_LOADER).includeResultPtr();
        });
        ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((context, include) -> {
        });
        // Generated guest shaders exceed the thread stack's fixed capacity; keep only short names there.
        var sourceBytes = MemoryUtil.memUTF8(source, false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_opengl,
                    Shaderc.shaderc_env_version_opengl_4_5);
            Shaderc.shaderc_compile_options_set_include_callbacks(options, resolver, release, 0);
            for (var define : defines.entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
            }
            var info = RenderSystem.getDevice().getDeviceInfo();
            if (info.isZZeroToOne()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
            }
            if (info.features().shaderDrawParameters()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options,
                        "RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE", "");
            }
            if (info.hintsAndWorkarounds().isExplicitDepthRequired()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE",
                        "");
            }
            result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, sourceBytes, kind,
                    stack.UTF8(label), stack.UTF8("main"), options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("Could not preprocess " + label + ": "
                        + Shaderc.shaderc_result_get_error_message(result));
            }
            return MemoryUtil.memUTF8(Shaderc.shaderc_result_get_bytes(result));
        } finally {
            MemoryUtil.memFree(sourceBytes);
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            resolver.close();
            release.close();
            includes.values().forEach(ShaderSource.CachedIncludeSource::close);
        }
    }

    private static ShaderSource.CachedIncludeSource loadInclude(String name) {
        try {
            Identifier id = Identifier.parse(name);
            try (var stream = Minecraft.getInstance().getResourceManager()
                    .getResource(id.withPrefix("shaders/include/")).orElseThrow().open()) {
                return ShaderSource.CachedIncludeSource.create(id,
                        new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException failure) {
            return ShaderSource.CachedIncludeSource.createError(new StringBuilder("Could not read include ")
                    .append(name).append(": ").append(failure.getMessage()).toString());
        }
    }
}
