package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;

import java.util.*;

public final class RenderPipelineCompiler {
    private static @Nullable Map<RenderPipeline, ShaderSource> collecting;

    private RenderPipelineCompiler() {
    }

    public static CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource source) {
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, source);
        if (!compiled.isValid()) throw new IllegalStateException("Failed to compile " + pipeline.getLocation());
        return compiled;
    }

    /**
     * Getters declare warmup requests here; ordinary first-use compilation remains synchronous and fail-fast.
     */
    public static void precompile(RenderPipeline pipeline, ShaderSource source) {
        if (collecting != null) collecting.putIfAbsent(pipeline, source);
        else compile(pipeline, source);
    }

    /**
     * Build the complete native graphics batch, then publish validated programs on the render thread.
     */
    public static void warm(Runnable declarations) {
        RenderSystem.assertOnRenderThread();
        Map<RenderPipeline, ShaderSource> requests = new LinkedHashMap<>();
        collecting = requests;
        try {
            declarations.run();
        } finally {
            collecting = null;
        }
        GlDevice device = (GlDevice) RenderSystem.getDevice().backend;
        Map<GlDevice.ShaderCompilationKey, GlShaderModule> pendingShaders = new LinkedHashMap<>();
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                GlRenderPipeline cached = device.pipelineCache.get(pipeline);
                if (cached != null) {
                    if (!cached.isValid())
                        throw new IllegalStateException("Failed to compile " + pipeline.getLocation());
                    continue;
                }
                GlShaderModule vertex = shader(device, pendingShaders, batch, pipeline.getVertexShader(),
                        ShaderType.VERTEX, pipeline, request.getValue());
                GlShaderModule fragment = shader(device, pendingShaders, batch, pipeline.getFragmentShader(),
                        ShaderType.FRAGMENT, pipeline, request.getValue());
                String[] attributes = attributes(pipeline);
                String label = pipeline.getLocation().toString();
                batch.link(label, new int[]{vertex.getShaderId(), fragment.getShaderId()}, attributes, handle -> {
                    GlProgram program = new GlProgram(handle, label);
                    program.setupBindGroupLayouts(pipeline.getBindGroupLayouts());
                    device.debugLabels.applyLabel(program);
                    device.pipelineCache.put(pipeline, new GlRenderPipeline(pipeline, program));
                });
            }
            batch.finish(() -> {
                for (var entry : pendingShaders.entrySet()) {
                    GlShaderModule module = entry.getValue();
                    device.debugLabels.applyLabel(module);
                    device.shaderCache.put(entry.getKey(), module);
                    batch.retainShader(module.getShaderId());
                }
            });
        }
    }

    private static GlShaderModule shader(GlDevice device, Map<GlDevice.ShaderCompilationKey, GlShaderModule> pending,
                                         GlCompilationBatch batch, Identifier id, ShaderType type,
                                         RenderPipeline pipeline, ShaderSource source) {
        var key = new GlDevice.ShaderCompilationKey(id, type, pipeline.getShaderDefines());
        GlShaderModule module = device.shaderCache.get(key);
        if (module == null) module = pending.get(key);
        if (module != null) {
            if (module == GlShaderModule.INVALID_SHADER) throw new IllegalStateException("Failed to compile " + key);
            return module;
        }
        String assembled = GlslPreprocessor.injectDefines(
                Objects.requireNonNull(source.get(id, type), "Missing shader source: " + key),
                pipeline.getShaderDefines());
        int handle = batch.shader(type == ShaderType.VERTEX ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER,
                assembled, key.toString());
        module = new GlShaderModule(handle, id, type);
        pending.put(key, module);
        return module;
    }

    private static String[] attributes(RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        String previous = null;
        for (var binding : pipeline.getVertexFormatBindings()) {
            if (binding == null) continue;
            for (var element : binding.getElements()) {
                String name = element.name();
                names.add(name.equals(previous) ? null : name);
                previous = name;
            }
        }
        return names.toArray(String[]::new);
    }
}
