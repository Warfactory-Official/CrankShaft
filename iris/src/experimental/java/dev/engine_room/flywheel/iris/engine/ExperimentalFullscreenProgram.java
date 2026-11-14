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
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.FramebufferDescription;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.PipelineState;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import org.joml.Vector2i;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renderer-owned fullscreen fragment kernel with depth writes on Iris's selected backend. Samplers bind under
 * {@code flywheel/oit/}; render thread only.
 */
public final class ExperimentalFullscreenProgram implements AutoCloseable {
    private final String name;
    private final BufferUniformHolder uniforms;
    private final ProgramResources resources;
    private final Map<PatchShaderType, String> sources;
    private final CompareOp depthCompare;
    private final Vector2i viewport = new Vector2i();
    private GraphicsShader shader;
    private GraphicsPipeline graphics;
    private Map<String, GpuTextureView> textures = Map.of();

    public ExperimentalFullscreenProgram(IrisRenderingPipeline parent, String resource, Consumer<Compilation> extra,
                                         CompareOp depthCompare, String... samplers) {
        name = "flywheel_" + resource.replace('.', '_');
        this.depthCompare = depthCompare;
        String fragment = NativeShaderPreprocessor.preprocessKind(source(resource, extra), name,
                Shaderc.shaderc_fragment_shader, Map.of());
        sources = Map.of(PatchShaderType.FRAGMENT, fragment, PatchShaderType.VERTEX,
                ShaderAssembly.assemble(ShaderAssembly.NO_EXTRA,
                        List.of(new ShaderAssembly.RawSource("fullscreen vertex", """
                                void main() {
                                    vec2 corner = vec2(gl_VertexID == 1 || gl_VertexID == 2 ? 1.0 : 0.0, gl_VertexID >= 2 ? 1.0 : 0.0);
                                    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                                }
                                """))));
        var layout = VulkanShaderPatcher.discover(sources).layout();
        uniforms = new BufferUniformHolder(layout);
        resources = new ProgramResources(parent.getPackSamplers(), layout);
        resources.addStorageBuffers(parent.getStorageBuffers());
        for (String sampler : samplers) {
            resources.addDrawTextureSampler(new TextureBinding("flywheel/oit/" + sampler,
                    () -> textures.get(sampler), () -> GlSampler.NEAREST), false, sampler);
        }
        resources.finish();
    }

    private static String source(String resource, Consumer<Compilation> extra) {
        String source = DeferredOitProfile.resource(resource);
        if (!source.startsWith("#version 430 core\n") && !source.startsWith("#version 430 core\r\n")) {
            throw new IllegalArgumentException("Unexpected fullscreen kernel version: " + resource);
        }
        Compilation compilation = new Compilation();
        compilation.version(GlslVersion.V430);
        extra.accept(compilation);
        compilation.appendComponent(new ShaderAssembly.RawSource(resource, source.substring(source.indexOf('\n') + 1)));
        return compilation.assembledSource();
    }

    public void draw(List<GpuTextureView> colors, GpuTextureView depth, Map<String, GpuTextureView> textures,
                     GpuBuffer indices, GpuBufferSlice command) {
        this.textures = textures;
        var target = colors.isEmpty() ? depth : colors.getFirst();
        int width = target.getWidth(0);
        int height = target.getHeight(0);
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
                                                 .withVertexShader(ResourceUtil.rl("iris/fullscreen"))
                                                 .withFragmentShader(ResourceUtil.rl("iris/fullscreen"))
                                                 .withDepthStencilState(new DepthStencilState(depthCompare, true))
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
        try (var pass = backend.createRenderPass(name, description, colors, depth, viewport.set(width, height))) {
            pass.setIndexBuffer(indices, IndexType.SHORT);
            pass.bindPipeline(graphics);
            pass.drawIndexedIndirect(command, 1);
        }
    }

    @Override
    public void close() {
        if (graphics != null) Iris.getRenderBackend().retire(graphics::close);
        if (shader != null) Iris.getRenderBackend().retire(shader::close);
    }
}
