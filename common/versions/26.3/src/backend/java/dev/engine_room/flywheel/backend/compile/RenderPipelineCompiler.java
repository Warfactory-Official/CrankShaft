package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.Uniform;
import com.mojang.renderpearl.backend.opengl.VertexArray;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.vulkan.VK12;

import java.util.*;

/**
 * Registers generated pipelines with Renderpearl's pass frontend. Native OIT storage/input descriptors
 * are supplied by their owning producer scope rather than Renderpearl's ordinary uniform list.
 * All registration, lookup and destruction runs on the render thread.
 */
public final class RenderPipelineCompiler {
    private static final Map<RenderPipeline, FrontendRenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, AssembledShaderSource> SOURCES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Map<String, Uniform>> UNIFORMS = new IdentityHashMap<>();
    private static final NativeResourceSource NATIVE_DEPTH_SOURCE = new NativeResourceSource(true);
    private static final NativeResourceSource NATIVE_COLOR_SOURCE = new NativeResourceSource(false);

    private static Map<RenderPipeline, AssembledShaderSource> collecting;
    private static Map<RenderPipeline, ProgramAvailability.Feature> collectingFeatures;
    private static ProgramAvailability.Feature feature;

    private RenderPipelineCompiler() {
    }

    public static CompiledRenderPipeline compile(RenderPipeline pipeline, AssembledShaderSource source) {
        RenderSystem.assertOnRenderThread();
        FrontendRenderPipeline cached = PIPELINES.get(pipeline);
        if (cached == null || cached.isClosed()) {
            warm(() -> precompile(pipeline, source));
            cached = Objects.requireNonNull(PIPELINES.get(pipeline));
        }
        return cached;
    }

    /** Declares inside {@link #warm}; synchronous otherwise. */
    public static void precompile(RenderPipeline pipeline, AssembledShaderSource source) {
        if (collecting != null) {
            collecting.putIfAbsent(pipeline, source);
            if (feature == null || !collectingFeatures.containsKey(pipeline)) collectingFeatures.put(pipeline, feature);
        } else compile(pipeline, source);
    }

    /** Resource-backed declaration in the current loading batch, with the owning pass's depth attachment contract. */
    public static void precompileNative(RenderPipeline pipeline, boolean depthAttachment) {
        precompile(pipeline, depthAttachment ? NATIVE_DEPTH_SOURCE : NATIVE_COLOR_SOURCE);
    }

    private record NativeResourceSource(boolean depthAttachment) implements AssembledShaderSource {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            Compilation compilation = new Compilation();
            compilation.version(ShaderAssembly.glslVersion());
            if (type == ShaderType.VERTEX) compilation.define("gl_VertexIndex", "gl_VertexID");
            compilation.appendComponent(
                    new ShaderAssembly.RawSource(id.toString(), NativeOitProducers.readShader(id, type)));
            return compilation.assembledSource();
        }
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
        Map<RenderPipeline, AssembledShaderSource> requests = new LinkedHashMap<>();
        Map<RenderPipeline, ProgramAvailability.Feature> features = new LinkedHashMap<>();
        collecting = requests;
        collectingFeatures = features;
        try {
            declarations.run();
        } finally {
            collecting = null;
            collectingFeatures = null;
        }
        if (VkContext.isVulkanHost()) {
            warmVulkan(requests, features);
            return;
        }
        int uncached = 0;
        for (RenderPipeline pipeline : requests.keySet()) {
            FrontendRenderPipeline cached = PIPELINES.get(pipeline);
            if (cached == null || cached.isClosed()) uncached++;
        }
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(uncached);
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                FrontendRenderPipeline cached = PIPELINES.get(pipeline);
                if (cached != null && !cached.isClosed()) {
                    continue;
                }
                requireSupportedPipeline(pipeline);
                int[] stages = new int[pipeline.getShaders().size()];
                int index = 0;
                for (var shader : pipeline.getShaders().entrySet()) {
                    String stage =
                            Objects.requireNonNull(request.getValue().getShader(shader.getValue(), shader.getKey()),
                            "Missing shader source: " + shader.getValue());
                    stages[index++] = batch.sharedShader(shader.getKey() == ShaderType.VERTEX
                                    ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER,
                            NativeShaderPreprocessor.preprocess(stage, shader.getValue().toString(),
                                    shader.getKey(), pipeline.getShaderDefines()), shader.getValue().toString());
                }
                batch.link(pipeline.getLocation().toString(), stages, attributes(pipeline),
                        handle -> publish(pipeline, request.getValue(), handle));
            }
            batch.finish(() -> {
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

    private static void warmVulkan(Map<RenderPipeline, AssembledShaderSource> requests,
                                   Map<RenderPipeline, ProgramAvailability.Feature> features) {
        List<PendingVulkanPipeline> pending = new ArrayList<>();
        ProgramAvailability.Feature owner = null;
        try (VkNativeFrontendCompiler compiler = new VkNativeFrontendCompiler()) {
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                FrontendRenderPipeline cached = PIPELINES.get(pipeline);
                if (cached != null && !cached.isClosed()) continue;
                owner = features.get(pipeline);
                AssembledShaderSource source = (id, stage) -> VkShaderTransform.toVulkan(
                        NativeShaderPreprocessor.preprocess(
                                Objects.requireNonNull(request.getValue().getShader(id, stage)),
                                id.toString(), stage, pipeline.getShaderDefines()),
                        stage == ShaderType.VERTEX ? VkShaderTransform.Stage.VERTEX : VkShaderTransform.Stage.FRAGMENT);
                int depthFormat = request.getValue() instanceof NativeResourceSource nativeSource
                        && !nativeSource.depthAttachment() ? VK12.VK_FORMAT_UNDEFINED : VkContext.mainDepthFormat();
                var compatibility = new VkNativePipelineCompiler.Compatibility(0, 0, depthFormat,
                        pipeline.getColorTargetStates());
                var compiled = compiler.compile(pipeline, source, Map.of(), compatibility);
                pending.add(new PendingVulkanPipeline(pipeline, request.getValue(), compiled));
            }
        } catch (RuntimeException failure) {
            pending.forEach(pipeline -> pipeline.compiled.close());
            if (owner != null && failure instanceof BackendUnavailableException unavailable
                    && !(failure instanceof ProgramAvailability.Failure)) {
                throw new ProgramAvailability.Failure(owner, unavailable);
            }
            throw failure;
        } catch (Error failure) {
            pending.forEach(pipeline -> pipeline.compiled.close());
            throw failure;
        }
        // Publish only after every requested stage and GPU pipeline has succeeded.
        for (var pipeline : pending) {
            FrontendRenderPipeline compiled = pipeline.compiled.frontend();
            RenderSystem.currentPipelineCache.insert(pipeline.pipeline, compiled);
            PIPELINES.put(pipeline.pipeline, compiled);
            SOURCES.put(pipeline.pipeline, pipeline.source);
        }
    }

    private record PendingVulkanPipeline(RenderPipeline pipeline, AssembledShaderSource source,
                                          VkNativeFrontendCompiler.CompiledPipeline compiled) {
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

    private static void publish(RenderPipeline pipeline, AssembledShaderSource source, int handle) {
        GlProgram program = NativeOitPipelines.createProgram(pipeline, handle);
        List<BindGroupLayout.UniformDescription> descriptions =
                BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        Object2IntOpenHashMap<String> indices = new Object2IntOpenHashMap<>();
        Map<String, Uniform> uniforms = setupUniforms(program, descriptions, indices);
        BackendRenderPipeline.CreateInfo createInfo = createInfo(pipeline, program, descriptions);
        GlDevice device = (GlDevice) ((FrontendGpuDevice) RenderSystem.getDevice()).backend;
        VertexArray vertexArray = VertexArray.createSource(GL.getCapabilities(), new HashSet<>())
                .apply(program, createInfo);
        GlRenderPipeline backend = new GlRenderPipeline(device, createInfo, program, vertexArray);
        FrontendRenderPipeline compiled = new FrontendRenderPipeline(pipeline.getLocation().toString(), backend,
                pipeline.getVertexFormatBindings(), indices, descriptions, createInfo.colorTargetStates(),
                pipeline.getDepthStencilState() != null, pipeline.pushConstantSize());
        RenderSystem.currentPipelineCache.insert(pipeline, compiled);
        PIPELINES.put(pipeline, compiled);
        SOURCES.put(pipeline, source);
        UNIFORMS.put(pipeline, uniforms);
    }

    public static CompiledRenderPipeline get(RenderPipeline pipeline) {
        FrontendRenderPipeline compiled = PIPELINES.get(pipeline);
        if (compiled == null) return RenderSystem.getCompiledPipeline(pipeline);
        // Vanilla can clear its pipeline cache without a full resource reload.
        return compiled.isClosed() ? compile(pipeline, SOURCES.get(pipeline)) : compiled;
    }

    public static Map<String, Uniform> uniforms(RenderPipeline pipeline) {
        return Objects.requireNonNull(UNIFORMS.get(pipeline), "Native pipeline has not been compiled: " + pipeline);
    }

    /** Registers a guest-owned program without transferring its destruction to the native cache. */
    public static FrontendRenderPipeline register(RenderPipeline pipeline, GlProgram program,
                                                   Map<String, Uniform> namedUniforms) {
        return register(pipeline, program, namedUniforms, pipeline.getDepthStencilState());
    }

    public static FrontendRenderPipeline register(RenderPipeline pipeline, GlProgram program,
                                                   Map<String, Uniform> namedUniforms, DepthStencilState nativeDepth) {
        return register(pipeline, program, namedUniforms, nativeDepth, null);
    }

    /** Guest-owned programs can expose different attribute names in the same vertex-format order. */
    public static FrontendRenderPipeline register(RenderPipeline pipeline, GlProgram program,
                                                   Map<String, Uniform> namedUniforms, DepthStencilState nativeDepth,
                                                   @Nullable String[] attributeNames) {
        requireSupportedPipeline(pipeline);
        var descriptions = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        Object2IntOpenHashMap<String> indices = new Object2IntOpenHashMap<>();
        for (int i = 0; i < descriptions.size(); i++) indices.put(descriptions.get(i).name(), i);
        var createInfo = createInfo(pipeline, program, descriptions, nativeDepth, attributeNames);
        GlDevice device = (GlDevice) ((FrontendGpuDevice) RenderSystem.getDevice()).backend;
        var vertexArray = VertexArray.createSource(GL.getCapabilities(), new HashSet<>()).apply(program, createInfo);
        var backend = new GlRenderPipeline(device, createInfo, program, vertexArray);
        var compiled = new FrontendRenderPipeline(pipeline.getLocation().toString(), backend,
                pipeline.getVertexFormatBindings(), indices, descriptions, createInfo.colorTargetStates(),
                pipeline.getDepthStencilState() != null, pipeline.pushConstantSize());
        UNIFORMS.put(pipeline, namedUniforms);
        return compiled;
    }

    public static void unregister(RenderPipeline pipeline) {
        UNIFORMS.remove(pipeline);
    }

    /** State variants own their VAO; their guest program is destroyed once by its program set. */
    public static void releaseGuestPipeline(FrontendRenderPipeline pipeline) {
        ((GlRenderPipeline) pipeline.backendRenderPipeline()).vertexArray().close();
    }

    public static void clearCache() {
        RenderSystem.currentPipelineCache.clear();
    }

    public static void reset() {
        PIPELINES.values().forEach(FrontendRenderPipeline::close);
        PIPELINES.keySet().forEach(UNIFORMS::remove);
        PIPELINES.clear();
        SOURCES.clear();
        VkNativeOitPipelines.reset();
        VkNativeOitPass.resetSpecs();
        NativeOitPipelines.reset();
        NativeOitWarmup.invalidate();
    }

    private static void requireSupportedPipeline(RenderPipeline pipeline) {
        if (pipeline.pushConstantSize() != 0 && !NativeOitPipelines.supportsPushConstants(pipeline)) {
            throw new UnsupportedOperationException("Native GL engine pipelines do not support push constants: "
                    + pipeline);
        }
    }

    private static Map<String, Uniform> setupUniforms(GlProgram program,
                                                     List<BindGroupLayout.UniformDescription> descriptions,
                                                     Object2IntOpenHashMap<String> indices) {
        Map<String, Uniform> byName = new HashMap<>();
        GlStateManager._glUseProgram(program.getProgramId());
        int nextUbo = program.pushConstant() == null ? 0 : 1;
        int nextSampler = 0;
        for (int i = 0; i < descriptions.size(); i++) {
            var description = descriptions.get(i);
            indices.put(description.name(), i);
            Uniform uniform = null;
            if (description.type() == UniformType.UNIFORM_BUFFER) {
                int block = GL31C.glGetUniformBlockIndex(program.getProgramId(), description.name());
                if (block != GL31C.GL_INVALID_INDEX) {
                    GL31C.glUniformBlockBinding(program.getProgramId(), block, nextUbo);
                    uniform = new Uniform.Ubo(nextUbo++);
                }
            } else {
                int location = GL20C.glGetUniformLocation(program.getProgramId(), description.name());
                if (location >= 0) {
                    int sampler = nextSampler++;
                    GL20C.glUniform1i(location, sampler);
                    uniform = description.type() == UniformType.TEXEL_BUFFER
                            ? new Uniform.Utb(sampler, description.gpuFormat()) : new Uniform.Sampler(sampler);
                }
            }
            program.uniforms.add(uniform);
            if (uniform != null) byName.put(description.name(), uniform);
        }
        GlStateManager._glUseProgram(0);
        return byName;
    }

    private static BackendRenderPipeline.CreateInfo createInfo(RenderPipeline pipeline, GlProgram program,
                                                                List<BindGroupLayout.UniformDescription> uniforms) {
        return createInfo(pipeline, program, uniforms, pipeline.getDepthStencilState());
    }

    private static BackendRenderPipeline.CreateInfo createInfo(RenderPipeline pipeline, GlProgram program,
                                                                List<BindGroupLayout.UniformDescription> uniforms,
                                                                DepthStencilState nativeDepth) {
        return createInfo(pipeline, program, uniforms, nativeDepth, null);
    }

    private static BackendRenderPipeline.CreateInfo createInfo(RenderPipeline pipeline, GlProgram program,
                                                                List<BindGroupLayout.UniformDescription> uniforms,
                                                                DepthStencilState nativeDepth,
                                                                @Nullable String[] attributeNames) {
        List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers = new ArrayList<>();
        List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes = new ArrayList<>();
        List<VertexFormat> formats = pipeline.getVertexFormatBindings();
        int attributeIndex = 0;
        for (int slot = 0; slot < formats.size(); slot++) {
            VertexFormat format = formats.get(slot);
            if (format == null) continue;
            buffers.add(new BackendRenderPipeline.CreateInfo.VertexBuffer(slot, format.getVertexSize(),
                    format.getStepRate()));
            String previousName = null;
            int previousLocation = -1;
            for (var element : format.getElements()) {
                String name = attributeNames == null ? element.name() : attributeNames[attributeIndex];
                attributeIndex++;
                // An optimized-out matrix has no active columns; -1 must never advance into location zero.
                int location = name.equals(previousName) ? (previousLocation >= 0 ? previousLocation + 1 : -1)
                        : GL20C.glGetAttribLocation(program.getProgramId(), name);
                if (location >= 0) attributes.add(new BackendRenderPipeline.CreateInfo.AttribBinding(slot, location,
                        element.offset(), element.format()));
                previousName = name;
                previousLocation = location;
            }
        }
        if (attributeNames != null && attributeIndex != attributeNames.length) {
            throw new IllegalArgumentException("Guest attribute names do not match the vertex formats");
        }
        return new BackendRenderPipeline.CreateInfo(pipeline.getLocation().toString(), List.of(), buffers, attributes,
                uniforms, pipeline.pushConstantSize(), nativeDepth, pipeline.getPolygonMode(),
                pipeline.isCull(), pipeline.getColorTargetStates(), pipeline.getPrimitiveTopology());
    }

}
