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
import dev.engine_room.flywheel.backend.FlwBackend;
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
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;

import java.util.*;
import java.util.function.Consumer;

/**
 * Registers generated pipelines with Renderpearl's pass frontend. Native OIT storage/input descriptors
 * are supplied by their owning producer scope rather than Renderpearl's ordinary uniform list.
 * All registration, lookup and destruction runs on the render thread.
 */
public final class RenderPipelineCompiler {
    private static final Map<RenderPipeline, FrontendRenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, AssembledShaderSource> SOURCES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Map<String, Uniform>> UNIFORMS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, GlAsyncCompiler.Pending> PENDING = new IdentityHashMap<>();
    private static final List<RenderPipeline> RELEASED = new ArrayList<>();
    private static final Map<RenderPipeline, List<RenderPipeline>> DERIVATIONS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> DERIVED_BASES = new IdentityHashMap<>();
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
            cancelPending(pipeline);
            warm(() -> precompile(pipeline, source));
            cached = Objects.requireNonNull(PIPELINES.get(pipeline));
        }
        return cached;
    }

    /**
     * Declares inside {@link #warm}; synchronous otherwise.
     */
    public static void precompile(RenderPipeline pipeline, AssembledShaderSource source) {
        if (collecting != null) {
            collecting.putIfAbsent(pipeline, source);
            if (feature == null || !collectingFeatures.containsKey(pipeline)) collectingFeatures.put(pipeline, feature);
        } else compile(pipeline, source);
    }

    /**
     * Draw-time compilation: GL with parallel compilation builds in the background and {@link #poll} publishes; Vulkan
     * hosts and GL without it compile now. Declares like {@link #precompile} inside {@link #warm}. A failure throws
     * from the compiling call or the publishing poll, attributed to {@code owner} when non-null.
     */
    public static void request(RenderPipeline pipeline, AssembledShaderSource source,
                               ProgramAvailability.@Nullable Feature owner) {
        if (collecting != null) {
            if (owner == null) precompile(pipeline, source);
            else feature(owner, () -> precompile(pipeline, source));
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (ready(pipeline) || PENDING.containsKey(pipeline)) return;
        if (VkContext.isVulkanHost() || !GlAsyncCompiler.available()) {
            if (owner == null) compile(pipeline, source);
            else ProgramAvailability.run(owner, () -> compile(pipeline, source));
            return;
        }
        requireSupportedPipeline(pipeline);
        int stageCount = pipeline.getShaders().size();
        int[] types = new int[stageCount];
        String[] sources = new String[stageCount];
        String[] labels = new String[stageCount];
        int index = 0;
        for (var shader : pipeline.getShaders().entrySet()) {
            String label = shader.getValue().toString();
            types[index] = shader.getKey() == ShaderType.VERTEX ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER;
            sources[index] = NativeShaderPreprocessor.preprocess(Objects.requireNonNull(
                            source.getShader(shader.getValue(), shader.getKey()), "Missing shader source: " + label),
                    label, shader.getKey(), pipeline.getShaderDefines());
            labels[index++] = label;
        }
        String[] attributes = attributes(pipeline);
        GlNativeProgramPool.Key key = nativeKey(pipeline, types, sources, attributes, owner);
        if (key == null) {
            PENDING.put(pipeline, GlAsyncCompiler.submit(pipeline.getLocation().toString(), types, sources, labels,
                    attributes, owner, null, handle -> {
                        PENDING.remove(pipeline);
                        publish(pipeline, source, handle);
                    }, () -> PENDING.remove(pipeline)));
        } else {
            PENDING.put(pipeline, GlNativeProgramRequests.request(key,
                    (callback, failure) -> GlAsyncCompiler.submit(pipeline.getLocation().toString(), types, sources,
                            labels,
                            attributes, owner, null, callback, failure),
                    entry -> {
                        PENDING.remove(pipeline);
                        publish(pipeline, source, entry);
                    }, () -> PENDING.remove(pipeline)));
        }
    }

    /**
     * Publishes {@code variant}, which differs from {@code base} only in fixed-function state, on base's linked
     * program without compiling: at once when base is ready and {@code now}, else when base publishes or, base being
     * ready, at the next {@link #poll}. Render thread, outside any render pass when {@code now}. {@code base}: a GL pipeline compiled by {@link #request} or {@link #warm}, whose
     * program the native pool leases.
     */
    static void derive(RenderPipeline base, RenderPipeline variant, boolean now) {
        if (now && ready(base)) {
            publishDerived(base, variant);
            return;
        }
        DERIVATIONS.computeIfAbsent(base, _ -> new ArrayList<>()).add(variant);
        DERIVED_BASES.put(variant, base);
    }

    private static void publishDerived(RenderPipeline base, RenderPipeline variant) {
        var program = (ProgramLease) ((GlRenderPipeline) PIPELINES.get(base).backendRenderPipeline()).program();
        publish(variant, SOURCES.get(base), program.entry.retain());
    }

    private static void publishDerivations(RenderPipeline base) {
        List<RenderPipeline> variants = DERIVATIONS.remove(base);
        if (variants == null) return;
        for (RenderPipeline variant : variants) {
            DERIVED_BASES.remove(variant);
            publishDerived(base, variant);
        }
    }

    public static boolean ready(RenderPipeline pipeline) {
        FrontendRenderPipeline compiled = PIPELINES.get(pipeline);
        return compiled != null && !compiled.isClosed();
    }

    /**
     * Building in the background, declared in the running {@link #warm}, or awaiting {@link #derive} publication.
     */
    public static boolean pending(RenderPipeline pipeline) {
        return PENDING.containsKey(pipeline) || DERIVED_BASES.containsKey(pipeline)
                || collecting != null && collecting.containsKey(pipeline);
    }

    /**
     * A superseded pipeline: cancelled while building or awaiting derivation, otherwise destroyed at the next
     * {@link #poll}.
     */
    public static void release(RenderPipeline pipeline) {
        RenderPipeline base = DERIVED_BASES.remove(pipeline);
        if (base != null) {
            List<RenderPipeline> variants = DERIVATIONS.get(base);
            variants.remove(pipeline);
            if (variants.isEmpty()) DERIVATIONS.remove(base);
        } else if (!cancelPending(pipeline)) {
            RELEASED.add(pipeline);
        }
    }

    /**
     * Render thread, at a frame boundary outside any pass: destroys released pipelines and publishes completed
     * background compilations.
     */
    public static void poll() {
        RenderSystem.assertOnRenderThread();
        if (!RELEASED.isEmpty()) {
            for (RenderPipeline pipeline : RELEASED) {
                FrontendRenderPipeline compiled = PIPELINES.remove(pipeline);
                SOURCES.remove(pipeline);
                UNIFORMS.remove(pipeline);
                if (compiled == null) continue;
                if (RenderSystem.currentPipelineCache != null) {
                    RenderSystem.currentPipelineCache.cache.remove(pipeline, compiled);
                }
                if (!compiled.isClosed()) compiled.close();
            }
            RELEASED.clear();
        }
        GlNativeProgramRequests.poll();
        GlAsyncCompiler.poll();
        pollDerivations();
    }

    // Bases that are neither ready nor pending failed, were released or lost to a cache reset.
    private static void pollDerivations() {
        for (var iterator = DERIVATIONS.entrySet().iterator(); iterator.hasNext(); ) {
            var derivation = iterator.next();
            RenderPipeline base = derivation.getKey();
            if (ready(base)) {
                for (RenderPipeline variant : derivation.getValue()) publishDerived(base, variant);
            } else if (pending(base)) {
                continue;
            }
            derivation.getValue().forEach(DERIVED_BASES::remove);
            iterator.remove();
        }
    }

    private static boolean cancelPending(RenderPipeline pipeline) {
        GlAsyncCompiler.Pending pending = PENDING.remove(pipeline);
        if (pending == null) return false;
        pending.cancel();
        return true;
    }

    /**
     * Resource-backed declaration in the current loading batch, with the owning pass's depth attachment contract.
     */
    public static void precompileNative(RenderPipeline pipeline, boolean depthAttachment) {
        precompile(pipeline, depthAttachment ? NATIVE_DEPTH_SOURCE : NATIVE_COLOR_SOURCE);
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

    /**
     * Render thread; declarations complete before validation/publication.
     */
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
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(0);
            Map<GlNativeProgramPool.Key, List<Consumer<GlNativeProgramPool.Entry>>> grouped =
                    new LinkedHashMap<>();
            List<Runnable> pooled = new ArrayList<>();
            int sharedRequests = 0;
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                FrontendRenderPipeline cached = PIPELINES.get(pipeline);
                if (cached != null && !cached.isClosed()) {
                    continue;
                }
                cancelPending(pipeline);
                requireSupportedPipeline(pipeline);
                int[] stages = new int[pipeline.getShaders().size()];
                int[] types = new int[stages.length];
                String[] sources = new String[stages.length];
                int index = 0;
                for (var shader : pipeline.getShaders().entrySet()) {
                    String stage =
                            Objects.requireNonNull(request.getValue().getShader(shader.getValue(), shader.getKey()),
                                    "Missing shader source: " + shader.getValue());
                    types[index] = shader.getKey() == ShaderType.VERTEX
                            ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER;
                    sources[index] = NativeShaderPreprocessor.preprocess(stage, shader.getValue().toString(),
                            shader.getKey(), pipeline.getShaderDefines());
                    stages[index] = batch.sharedShader(types[index], sources[index], shader.getValue().toString());
                    index++;
                }
                String[] attributes = attributes(pipeline);
                GlNativeProgramPool.Key key = nativeKey(pipeline, types, sources, attributes, features.get(pipeline));
                if (key == null) {
                    batch.link(pipeline.getLocation().toString(), stages, attributes,
                            handle -> publish(pipeline, request.getValue(), handle));
                    continue;
                }
                sharedRequests++;
                var group = grouped.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    grouped.put(key, group);
                    var members = group;
                    if (GlNativeProgramPool.contains(key)) {
                        pooled.add(() -> members.forEach(member ->
                                member.accept(Objects.requireNonNull(GlNativeProgramPool.acquire(key)))));
                    } else {
                        batch.link(pipeline.getLocation().toString(), stages, attributes, handle -> {
                            GlNativeProgramPool.Entry entry = GlNativeProgramPool.adopt(key, handle);
                            for (int i = 0; i < members.size(); i++) {
                                if (i != 0) entry = Objects.requireNonNull(GlNativeProgramPool.acquire(key));
                                members.get(i).accept(entry);
                            }
                        });
                    }
                }
                group.add(entry -> publish(pipeline, request.getValue(), entry));
            }
            FlwBackend.LOGGER.info("native GL program sharing: {} requests, {} distinct keys",
                    sharedRequests, grouped.size());
            batch.finish(() -> pooled.forEach(Runnable::run));
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
                int depthFormat = request.getValue() instanceof NativeResourceSource(boolean depthAttachment)
                        && !depthAttachment ? VK10.VK_FORMAT_UNDEFINED : VkContext.mainDepthFormat();
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

    private static GlNativeProgramPool.@Nullable Key nativeKey(RenderPipeline pipeline, int[] types, String[] sources,
                                                               String[] attributes,
                                                               ProgramAvailability.@Nullable Feature owner) {
        if (pipeline instanceof GlPipelineProgram) return null;
        return GlNativeProgramPool.key(types, sources, attributes,
                BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()), List.of(), owner);
    }

    private static final class ProgramLease extends GlProgram {
        private final GlNativeProgramPool.Entry entry;
        private boolean closed;

        private ProgramLease(GlNativeProgramPool.Entry entry, String label) {
            super(entry.handle(), label);
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            uniforms.forEach(uniform -> {
                if (uniform != null) uniform.close();
            });
            entry.release();
        }
    }

    private static void publish(RenderPipeline pipeline, AssembledShaderSource source,
                                GlNativeProgramPool.Entry entry) {
        publish(pipeline, source, new ProgramLease(entry, pipeline.getLocation().toString()));
    }

    private static void publish(RenderPipeline pipeline, AssembledShaderSource source, int handle) {
        GlProgram program = pipeline instanceof GlPipelineProgram custom ? custom.createGlProgram(handle)
                : new GlProgram(handle, pipeline.getLocation().toString());
        publish(pipeline, source, program);
    }

    private static void publish(RenderPipeline pipeline, AssembledShaderSource source, GlProgram program) {
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
        publishDerivations(pipeline);
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

    /**
     * Registers a guest-owned program without transferring its destruction to the native cache.
     */
    public static FrontendRenderPipeline register(RenderPipeline pipeline, GlProgram program,
                                                  Map<String, Uniform> namedUniforms) {
        return register(pipeline, program, namedUniforms, pipeline.getDepthStencilState());
    }

    public static FrontendRenderPipeline register(RenderPipeline pipeline, GlProgram program,
                                                  Map<String, Uniform> namedUniforms, DepthStencilState nativeDepth) {
        return register(pipeline, program, namedUniforms, nativeDepth, null);
    }

    /**
     * Guest-owned programs can expose different attribute names in the same vertex-format order.
     */
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

    /**
     * State variants own their VAO; their guest program is destroyed once by its program set.
     */
    public static void releaseGuestPipeline(FrontendRenderPipeline pipeline) {
        ((GlRenderPipeline) pipeline.backendRenderPipeline()).vertexArray().close();
    }

    public static void clearCache() {
        RenderSystem.currentPipelineCache.clear();
    }

    public static void reset() {
        // Worker builds may target the native passes reset below.
        VkAsyncBuilds.join();
        PENDING.values().forEach(GlAsyncCompiler.Pending::cancel);
        PENDING.clear();
        RELEASED.clear();
        DERIVATIONS.clear();
        DERIVED_BASES.clear();
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
        if (pipeline.pushConstantSize() != 0 && !(pipeline instanceof GlPipelineProgram custom && custom.supportsGlPushConstants())) {
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

    private record PendingVulkanPipeline(RenderPipeline pipeline, AssembledShaderSource source,
                                         VkNativeFrontendCompiler.CompiledPipeline compiled) {
    }

}
