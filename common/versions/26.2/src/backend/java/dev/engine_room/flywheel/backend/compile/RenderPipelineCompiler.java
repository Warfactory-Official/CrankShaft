package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.FlwBackend;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;

import java.util.*;
import java.util.function.Consumer;

public final class RenderPipelineCompiler {
    private static final Map<RenderPipeline, GlAsyncCompiler.Pending> PENDING = new IdentityHashMap<>();
    private static final List<RenderPipeline> RELEASED = new ArrayList<>();
    private static final Map<RenderPipeline, List<RenderPipeline>> DERIVATIONS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> DERIVED_BASES = new IdentityHashMap<>();
    private static @Nullable Map<RenderPipeline, ShaderSource> collecting;
    private static @Nullable Map<RenderPipeline, ProgramAvailability.Feature> collectingFeatures;
    private static ProgramAvailability.@Nullable Feature feature;

    private RenderPipelineCompiler() {
    }

    public static CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource source) {
        if (RenderSystem.getDevice().backend instanceof GlDevice device) {
            GlRenderPipeline cached = device.pipelineCache.get(pipeline);
            if (cached == null) {
                cancelPending(pipeline);
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

    /**
     * Declares inside {@link #warm}; synchronous otherwise.
     */
    public static void precompile(RenderPipeline pipeline, ShaderSource source) {
        if (collecting != null) {
            collecting.putIfAbsent(pipeline, source);
            if (feature == null || !collectingFeatures.containsKey(pipeline)) collectingFeatures.put(pipeline, feature);
        } else compile(pipeline, source);
    }

    /**
     * Draw-time compilation: GL with parallel compilation builds in the background and {@link #poll} publishes;
     * without it, compiles now. Declares like {@link #precompile} inside {@link #warm}. A failure throws from the
     * compiling call or the publishing poll, attributed to {@code owner} when non-null.
     */
    public static void request(RenderPipeline pipeline, ShaderSource source,
                               ProgramAvailability.@Nullable Feature owner) {
        if (collecting != null) {
            if (owner == null) precompile(pipeline, source);
            else feature(owner, () -> precompile(pipeline, source));
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (ready(pipeline) || PENDING.containsKey(pipeline)) return;
        if (!GlAsyncCompiler.available()) {
            if (owner == null) compile(pipeline, source);
            else ProgramAvailability.run(owner, () -> compile(pipeline, source));
            return;
        }
        GlDevice device = (GlDevice) RenderSystem.getDevice().backend;
        String label = pipeline.getLocation().toString();
        var vertex = new GlDevice.ShaderCompilationKey(pipeline.getVertexShader(), ShaderType.VERTEX,
                pipeline.getShaderDefines());
        var fragment = new GlDevice.ShaderCompilationKey(pipeline.getFragmentShader(), ShaderType.FRAGMENT,
                pipeline.getShaderDefines());
        int[] types = {GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER};
        String[] sources = {stageSource(source, vertex, pipeline), stageSource(source, fragment, pipeline)};
        String[] attributes = attributes(pipeline);
        GlNativeProgramPool.Key key = nativeKey(pipeline, types, sources, attributes, owner);
        PENDING.put(pipeline, GlNativeProgramRequests.request(key,
                (callback, failure) -> GlAsyncCompiler.submit(label, types, sources,
                        new String[]{vertex.toString(), fragment.toString()}, attributes, owner, null, callback,
                        failure),
                entry -> {
                    PENDING.remove(pipeline);
                    publish(device, pipeline, entry);
                }, () -> PENDING.remove(pipeline)));
    }

    /**
     * Publishes {@code variant}, which differs from {@code base} only in fixed-function state, on base's linked
     * program without compiling: at once when base is ready and {@code now}, else when base publishes or, base being
     * ready, at the next {@link #poll}. Render thread. {@code base}: a GL pipeline compiled by {@link #request} or {@link #warm}, whose
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
        GlDevice device = (GlDevice) RenderSystem.getDevice().backend;
        var program = (ProgramLease) device.pipelineCache.get(base).program();
        publish(device, variant, program.entry.retain());
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
        GlRenderPipeline compiled = ((GlDevice) RenderSystem.getDevice().backend).pipelineCache.get(pipeline);
        return compiled != null && compiled.isValid();
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
            GlDevice device = (GlDevice) RenderSystem.getDevice().backend;
            for (RenderPipeline pipeline : RELEASED) {
                GlRenderPipeline compiled = device.pipelineCache.remove(pipeline);
                if (compiled != null && compiled.isValid()) compiled.program().close();
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

    private static String stageSource(ShaderSource source, GlDevice.ShaderCompilationKey key, RenderPipeline pipeline) {
        return GlslPreprocessor.injectDefines(Objects.requireNonNull(source.get(key.id(), key.type()),
                "Missing shader source: " + key), pipeline.getShaderDefines());
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
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(0);
            Map<GlNativeProgramPool.Key, List<Consumer<GlNativeProgramPool.Entry>>> grouped =
                    new LinkedHashMap<>();
            List<Runnable> pooled = new ArrayList<>();
            int sharedRequests = 0;
            for (var request : requests.entrySet()) {
                RenderPipeline pipeline = request.getKey();
                GlRenderPipeline cached = device.pipelineCache.get(pipeline);
                if (cached != null) {
                    if (!cached.isValid()) {
                        throw new BackendUnavailableException("Failed to compile " + pipeline.getLocation());
                    }
                    continue;
                }
                cancelPending(pipeline);
                GlShaderModule vertex = shader(device, pendingShaders, batch, pipeline.getVertexShader(),
                        ShaderType.VERTEX, pipeline, request.getValue());
                GlShaderModule fragment = shader(device, pendingShaders, batch, pipeline.getFragmentShader(),
                        ShaderType.FRAGMENT, pipeline, request.getValue());
                String[] attributes = attributes(pipeline);
                int[] stages = {vertex.getShaderId(), fragment.getShaderId()};
                int[] types = {GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER};
                String[] sources = {batch.source(stages[0]), batch.source(stages[1])};
                GlNativeProgramPool.Key key = nativeKey(pipeline, types, sources, attributes, features.get(pipeline));
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
                group.add(entry -> publish(device, pipeline, entry));
            }
            FlwBackend.LOGGER.info("native GL program sharing: {} requests, {} distinct keys",
                    sharedRequests, grouped.size());
            batch.finish(() -> {
                for (var entry : pendingShaders.entrySet()) {
                    GlShaderModule module = entry.getValue();
                    if (!batch.compiled(module.getShaderId())) continue;
                    device.debugLabels.applyLabel(module);
                    device.shaderCache.put(entry.getKey(), module);
                    batch.retainShader(module.getShaderId());
                }
                pooled.forEach(Runnable::run);
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
            if (module == GlShaderModule.INVALID_SHADER) {
                throw new BackendUnavailableException("Failed to compile " + key);
            }
            return module;
        }
        int handle = batch.shader(type == ShaderType.VERTEX ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER,
                stageSource(source, key, pipeline), key.toString());
        module = new GlShaderModule(handle, id, type);
        pending.put(key, module);
        return module;
    }

    private static GlNativeProgramPool.Key nativeKey(RenderPipeline pipeline, int[] types, String[] sources,
                                                     String[] attributes,
                                                     ProgramAvailability.@Nullable Feature owner) {
        return GlNativeProgramPool.key(types, sources, attributes,
                BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()),
                BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()), owner);
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
            getUniforms().values().forEach(Uniform::close);
            entry.release();
        }
    }

    private static void publish(GlDevice device, RenderPipeline pipeline, GlNativeProgramPool.Entry entry) {
        GlProgram program = new ProgramLease(entry, pipeline.getLocation().toString());
        program.setupBindGroupLayouts(pipeline.getBindGroupLayouts());
        device.debugLabels.applyLabel(program);
        device.pipelineCache.put(pipeline, new GlRenderPipeline(pipeline, program));
        publishDerivations(pipeline);
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

    public static CompiledRenderPipeline get(RenderPipeline pipeline) {
        return RenderSystem.getDevice().precompilePipeline(pipeline);
    }

    public static Map<String, Uniform> uniforms(RenderPipeline pipeline) {
        return ((GlRenderPipeline) get(pipeline)).program().getUniforms();
    }

    public static void clearCache() {
        RenderSystem.getDevice().clearPipelineCache();
    }

    public static void reset() {
        // Vanilla's shader reload owns the 26.2 device cache.
        PENDING.values().forEach(GlAsyncCompiler.Pending::cancel);
        PENDING.clear();
        RELEASED.clear();
        DERIVATIONS.clear();
        DERIVED_BASES.clear();
    }
}
