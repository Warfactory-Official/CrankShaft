package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.backend.FramebufferDescription;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.PipelineState;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Renderer-owned replay kernels on Iris's selected backend.
 */
public final class ExperimentalReplayProgram implements AutoCloseable {
    private static final List<String> SAMPLERS = List.of("saved0", "saved1", "saved2", "savedDepth", "saved5",
            "savedCloud",
            "backDepth", "color", "weather", "depth", "savedOpaque", "backColor", "savedMotion", "opaqueDepth",
            "saved0Main", "saved0Alt", "saved7Main", "saved7Alt", "_flw_depthRange");
    private final String name;
    private final BufferUniformHolder uniforms;
    private final ProgramResources resources;
    private final Map<PatchShaderType, String> sources;
    private final boolean depth;
    private final CompareOp depthCompare;
    private GraphicsShader shader;
    private GraphicsPipeline graphics;
    private ComputePipeline compute;
    private Map<String, GpuTextureView> textures = Map.of();
    private Map<String, List<GpuBufferSlice>> buffers = Map.of();
    private final Vector2i viewport = new Vector2i();
    private int width;
    private int layer;
    private int height;

    public ExperimentalReplayProgram(IrisRenderingPipeline parent, String resource, boolean compute, boolean depth,
                                     Consumer<Compilation> extra, Set<String> external) {
        this(parent, resource, compute, depth, extra, external, CompareOp.ALWAYS_PASS);
    }

    public ExperimentalReplayProgram(IrisRenderingPipeline parent, String resource, boolean compute, boolean depth,
                                     Consumer<Compilation> extra, Set<String> external, CompareOp depthCompare) {
        name = "flywheel_replay_" + resource.replace('.', '_');
        this.depth = depth;
        this.depthCompare = depthCompare;
        String source = DeferredReplayShaders.source(resource, extra);
        source = NativeShaderPreprocessor.preprocessKind(source, name,
                compute ? Shaderc.shaderc_compute_shader : Shaderc.shaderc_fragment_shader, Map.of());
        if (compute) sources = Map.of(PatchShaderType.COMPUTE, source);
        else sources = Map.of(PatchShaderType.FRAGMENT, source, PatchShaderType.VERTEX,
                ShaderAssembly.assemble(ShaderAssembly.NO_EXTRA,
                        List.of(new ShaderAssembly.RawSource("replay fullscreen vertex", """
                                void main() {
                                    vec2 corner = vec2(gl_VertexID == 1 || gl_VertexID == 2 ? 1.0 : 0.0, gl_VertexID >= 2 ? 1.0 : 0.0);
                                    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                                }
                                """))));
        var layout = VulkanShaderPatcher.discover(sources).layout();
        uniforms = new BufferUniformHolder(layout);
        uniforms.uniform1i(UniformUpdateFrequency.CUSTOM, "width", () -> width);
        uniforms.uniform1i(UniformUpdateFrequency.CUSTOM, "layerIndex", () -> layer);
        uniforms.uniform2f(UniformUpdateFrequency.CUSTOM, "screenSize", () -> new Vector2f(width, height));
        uniforms.uniform1b(UniformUpdateFrequency.CUSTOM, "flw_oitActive", () -> true);
        resources = new ProgramResources(parent.getPackSamplers(), layout);
        resources.flywheel$externalStorage(external);
        resources.addStorageBuffers(parent.getStorageBuffers());
        resources.setUniformBuffers(() -> buffers);
        for (String sampler : SAMPLERS) {
            resources.addDrawTextureSampler(new TextureBinding(
                    (resource.equals("native_oit_depth.frag") ? "flywheel/oit/" : "flywheel/replay/") + sampler,
                    () -> textures.get(sampler), () -> GlSampler.NEAREST), false, sampler);
        }
        resources.finish();
        if (compute) {
            this.compute = Iris.getRenderBackend().createComputePipeline(name,
                    TransformPatcher.patchProgram(name, sources, resources, uniforms, null), resources, uniforms);
        }
    }

    /**
     * Render-thread registration during pipeline construction, before Iris seals the sampler heap.
     */
    public static void registerSamplers(IrisRenderingPipeline parent) {
        var resolver = ((IrisRenderingPipelineAccessor) parent).flywheel$resolver();
        var programs = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
        var profile = programs.flywheel$deferredOit();
        if (profile == null) return;
        var samplers = switch (profile) {
            case SUNDIAL -> List.of("saved0", "saved1", "saved2", "savedDepth", "saved5", "savedCloud",
                    "backDepth", "color", "weather", "depth");
            case SEUS, SEUS_HRR3 -> List.of("saved1", "saved2", "savedDepth", "savedOpaque", "backColor", "backDepth",
                    "opaqueDepth", "depth", "color", "saved7Main", "saved7Alt", "saved0Main", "saved0Alt",
                    "savedMotion");
            case ITERATION -> throw new UnsupportedOperationException("Experimental Iris does not support iterationRP");
        };
        for (String name : samplers)
            parent.getPackSamplers().register(SamplerHeapLayout.Type.SAMPLER2D,
                    "flywheel/replay/" + name, true);
    }

    void dispatch(Map<String, List<GpuBufferSlice>> buffers, Vector3i groups) {
        this.buffers = buffers;
        uniforms.update(0, 0);
        Iris.getRenderBackend().commands().dispatch(compute, groups, null);
    }

    void dispatch(Map<String, GpuTextureView> textures, int width, int height, Vector3i groups) {
        this.textures = textures;
        this.width = width;
        this.height = height;
        dispatch(Map.of(), groups);
    }

    public void draw(List<GpuTextureView> colors, GpuTextureView depth, Map<String, GpuTextureView> textures,
                     GpuBuffer indices, GpuBufferSlice command, int layer) {
        this.textures = textures;
        var target = colors.isEmpty() ? depth : colors.getFirst();
        width = target.getWidth(0);
        height = target.getHeight(0);
        this.layer = layer;
        var description = graphics == null ? null : graphics.framebuffer();
        boolean matches = description != null && description.colorFormats().size() == colors.size()
                && description.depthFormat() == (depth == null ? null : depth.texture().getFormat());
        for (int i = 0; matches && i < colors.size(); i++)
            matches = description.colorFormats().get(i) == colors.get(i).texture().getFormat();
        if (!matches) {
            description = new FramebufferDescription(colors.stream().map(view -> view.texture().getFormat()).toList(),
                    depth == null ? null : depth.texture().getFormat());
            if (graphics != null) Iris.getRenderBackend().retire(graphics::close);
            if (shader == null) {
                shader = Iris.getRenderBackend().createGraphicsShader(name,
                        TransformPatcher.patchProgram(name, sources, resources, uniforms, null), resources, uniforms);
                uniforms.resolveLayout(shader.getSource().layout());
            }
            RenderPipeline state = RenderPipeline.builder().withLocation(ResourceUtil.rl(name))
                                                 .withVertexShader(ResourceUtil.rl("iris/replay"))
                                                 .withFragmentShader(ResourceUtil.rl("iris/replay"))
                                                 .withDepthStencilState(new DepthStencilState(depthCompare, this.depth))
                                                 .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                                                 .build();
            graphics = shader.createPipeline(new PipelineState(description,
                    Collections.nCopies(colors.size(), null), List.of(), 0, state, false, null, List.of()));
        }
        uniforms.update(0, 0);
        var backend = Iris.getRenderBackend();
        var sync = backend.createSync().read(graphics).read(command, SyncCommand.Access.INDIRECT);
        for (int i = 0; i < colors.size(); i++) sync.write(colors.get(i), SyncCommand.Access.COLOR_ATTACHMENT);
        if (depth != null) sync.write(depth, SyncCommand.Access.DEPTH_ATTACHMENT);
        sync.nextIs(SyncCommand.Stage.GRAPHICS).run();
        try (var pass = backend.createRenderPass(name, description, colors, depth,
                viewport.set(width, height))) {
            pass.setIndexBuffer(indices, IndexType.SHORT);
            pass.bindPipeline(graphics);
            pass.drawIndexedIndirect(command, 1);
        }
    }

    @Override
    public void close() {
        if (graphics != null) Iris.getRenderBackend().retire(graphics::close);
        if (shader != null) Iris.getRenderBackend().retire(shader::close);
        if (compute != null) Iris.getRenderBackend().retire(compute::close);
    }
}
