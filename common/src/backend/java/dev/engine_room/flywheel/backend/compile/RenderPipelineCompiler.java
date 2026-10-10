package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;

import java.util.*;

public final class RenderPipelineCompiler {
    private static @Nullable Map<RenderPipeline, ShaderSource> collecting;
    private static @Nullable Map<RenderPipeline, ProgramAvailability.Feature> collectingFeatures;
    private static ProgramAvailability.@Nullable Feature feature;

    private RenderPipelineCompiler() {
    }

    public static CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource source) {
        if (RenderSystem.getDevice().backend instanceof GlDevice device) {
            GlRenderPipeline cached = device.pipelineCache.get(pipeline);
            if (cached == null) {
                warm(() -> precompile(pipeline, source));
                cached = Objects.requireNonNull(device.pipelineCache.get(pipeline));
            }
            if (!cached.isValid()) {
                String message = "Failed to compile " + pipeline.getLocation();
                int error = GlStateManager._getError();
                if (error == GL11C.GL_OUT_OF_MEMORY) throw new OutOfMemoryError(message);
                if (error == GL45C.GL_CONTEXT_LOST) throw new IllegalStateException("OpenGL context lost: " + message);
                throw new BackendUnavailableException(message);
            }
            return cached;
        }
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, source);
        if (!compiled.isValid()) throw new BackendUnavailableException("Failed to compile " + pipeline.getLocation());
        return compiled;
    }

    /** Declares inside {@link #warm}; synchronous otherwise. */
    public static void precompile(RenderPipeline pipeline, ShaderSource source) {
        if (collecting != null) {
            collecting.putIfAbsent(pipeline, source);
            if (feature == null || !collectingFeatures.containsKey(pipeline)) collectingFeatures.put(pipeline, feature);
        } else compile(pipeline, source);
    }

    public static void feature(ProgramAvailability.Feature owner, Runnable declarations) {
        if (collecting == null) {
            ProgramAvailability.run(owner, declarations);
            return;
        }
        if (!ProgramAvailability.allows(owner)) return;
        ProgramAvailability.Feature previous = feature;
        feature = owner;
        try {
            declarations.run();
        } catch (ProgramAvailability.Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            throw new ProgramAvailability.Failure(owner, failure);
        } finally {
            feature = previous;
        }
    }

    /** Render thread; declarations complete before validation/publication. */
    public static void warm(Runnable declarations) {
        RenderSystem.assertOnRenderThread();
        Map<RenderPipeline, ShaderSource> requests = new LinkedHashMap<>();
        Map<RenderPipeline, ProgramAvailability.Feature> features = new LinkedHashMap<>();
        collecting = requests;
        collectingFeatures = features;
        try {
            declarations.run();
        } finally {
            collecting = null;
            collectingFeatures = null;
        }
        GlDevice device = (GlDevice) RenderSystem.getDevice().backend;
        Map<GlDevice.ShaderCompilationKey, GlShaderModule> pendingShaders = new LinkedHashMap<>();
        int uncached = 0;
        for (RenderPipeline pipeline : requests.keySet()) if (device.pipelineCache.get(pipeline) == null) uncached++;
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(uncached);
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                GlRenderPipeline cached = device.pipelineCache.get(pipeline);
                if (cached != null) {
                    if (!cached.isValid())
                        throw new BackendUnavailableException("Failed to compile " + pipeline.getLocation());
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
                    if (!batch.compiled(module.getShaderId())) continue;
                    device.debugLabels.applyLabel(module);
                    device.shaderCache.put(entry.getKey(), module);
                    batch.retainShader(module.getShaderId());
                }
            });
        } catch (GlCompilationBatch.Failure failure) {
            ProgramAvailability.Feature owner = null;
            for (RenderPipeline pipeline : requests.keySet()) {
                if (!failure.programs().contains(pipeline.getLocation().toString())) continue;
                ProgramAvailability.Feature candidate = features.get(pipeline);
                if (candidate == null) throw failure;
                if (owner == null) owner = candidate;
            }
            if (owner != null) throw new ProgramAvailability.Failure(owner, failure);
            throw failure;
        }
    }

    private static GlShaderModule shader(GlDevice device, Map<GlDevice.ShaderCompilationKey, GlShaderModule> pending,
                                         GlCompilationBatch batch, Identifier id, ShaderType type,
                                         RenderPipeline pipeline, ShaderSource source) {
        var key = new GlDevice.ShaderCompilationKey(id, type, pipeline.getShaderDefines());
        GlShaderModule module = device.shaderCache.get(key);
        if (module == null) module = pending.get(key);
        if (module != null) {
            if (module == GlShaderModule.INVALID_SHADER) throw new BackendUnavailableException("Failed to compile " + key);
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
