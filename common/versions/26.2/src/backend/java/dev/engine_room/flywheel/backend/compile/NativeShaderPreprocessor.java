package dev.engine_room.flywheel.backend.compile;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.Map;

public final class NativeShaderPreprocessor {
    private NativeShaderPreprocessor() {
    }

    public static String preprocessKind(String source, String label, int kind, Map<String, String> defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0;
        var bytes = MemoryUtil.memUTF8(MojImportPreprocessor.flatten(source), false);
        try (var stack = MemoryStack.stackPush()) {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_opengl,
                    Shaderc.shaderc_env_version_opengl_4_5);
            for (var define : defines.entrySet())
                Shaderc.shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
            result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, bytes, kind,
                    stack.UTF8(label), stack.UTF8("main"), options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                throw new IllegalStateException(
                        "Could not preprocess " + label + ": " + Shaderc.shaderc_result_get_error_message(result));
            return MemoryUtil.memUTF8(Shaderc.shaderc_result_get_bytes(result));
        } finally {
            MemoryUtil.memFree(bytes);
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
