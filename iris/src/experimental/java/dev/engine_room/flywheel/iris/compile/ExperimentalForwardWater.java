package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
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
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.targets.RenderFramebuffer;
import net.irisshaders.iris.targets.WorldRenderTargets.Phase;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import org.joml.Vector2i;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Restores the nearest native translucent surface before the pack's composite passes consume its G-buffer.
 */
final class ExperimentalForwardWater implements AutoCloseable {
    private final GraphicsShader shader;
    private final GraphicsPipeline pipeline;
    private final BufferUniformHolder uniforms;
    private final RenderFramebuffer framebuffer;
    private final FramebufferDescription description;
    private final GpuBuffer indices;
    private final GpuBuffer command;
    private final Vector2i viewport = new Vector2i();

    ExperimentalForwardWater(IrisRenderingPipeline parent, ForwardOit forward,
                             Map<String, GpuTextureView> textures, GpuBuffer indices, GpuBuffer command) {
        this.indices = indices;
        this.command = command;
        String name = "flywheel_forward_native_water";
        var attachments = new ColorAttachmentPlan.Builder().program(name, forward.waterBuffers(),
                                                                   parent.getWorldTargets().getFlippedBuffers(Phase.TRANSLUCENT))
                                                           .build(RenderSystem.getDevice().getDeviceInfo().limits()
                                                                              .maxColorAttachments());
        var outputs = attachments.program(name);
        framebuffer = ((IrisRenderingPipelineAccessor) parent).flywheel$renderTargets()
                                                              .createMergedFramebuffer(attachments, true);
        String vertex = ShaderAssembly.assemble(ShaderAssembly.NO_EXTRA,
                List.of(new ShaderAssembly.RawSource("forward water fullscreen quad", """
                        void main() {
                            vec2 corner = vec2(gl_VertexID == 1 || gl_VertexID == 2 ? 1.0 : 0.0, gl_VertexID >= 2 ? 1.0 : 0.0);
                            gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                        }
                        """)));
        var sources = Map.of(PatchShaderType.VERTEX, NativeShaderPreprocessor.preprocessKind(vertex, name,
                        Shaderc.shaderc_vertex_shader, Map.of()), PatchShaderType.FRAGMENT,
                NativeShaderPreprocessor.preprocessKind(GuestOitCodegen.waterFragment(forward), name,
                        Shaderc.shaderc_fragment_shader, Map.of()));
        var layout = VulkanShaderPatcher.discover(sources).layout();
        uniforms = new BufferUniformHolder(layout);
        var resources = new ProgramResources(parent.getPackSamplers(), layout);
        var samplers = new ArrayList<String>();
        samplers.add("_flw_depthRange");
        for (int slot = 0; slot < forward.waterBuffers().length; slot++)
            samplers.add("_flw_forwardWaterRead" + slot);
        for (String sampler : samplers)
            resources.addDrawTextureSampler(new TextureBinding("flywheel/oit/" + sampler,
                    () -> textures.get(sampler), () -> GlSampler.NEAREST), false, sampler);
        resources.finish();
        shader = Iris.getRenderBackend().createGraphicsShader(name,
                TransformPatcher.patchProgram(name, sources, resources, uniforms, outputs), resources, uniforms);
        uniforms.resolveLayout(shader.getSource().layout());
        var colors = framebuffer.getColorViews();
        description = new FramebufferDescription(colors.stream().map(view -> view.texture().getFormat()).toList(),
                framebuffer.getDepthView().texture().getFormat());
        var state = RenderPipeline.builder().withLocation(ResourceUtil.rl(name))
                                  .withVertexShader(ResourceUtil.rl("iris/forward_water"))
                                  .withFragmentShader(ResourceUtil.rl("iris/forward_water"))
                                  .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                                  .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var blends = ExperimentalPipelines.blends(forward.waterBlend().blendMode(), forward.waterBufferBlends(),
                forward.waterBuffers());
        pipeline = shader.createPipeline(new PipelineState(description, outputs.remapBlends(blends),
                List.of(), 0, state, false, null, List.of()));
    }

    void draw(GpuTextureView depth, ForwardOit.SnapshotRect rectangle) {
        uniforms.update(0, 0);
        var colors = framebuffer.getColorViews();
        var backend = Iris.getRenderBackend();
        var sync = backend.createSync().read(pipeline).read(command, SyncCommand.Access.INDIRECT);
        for (var color : colors) sync.write(color, SyncCommand.Access.COLOR_ATTACHMENT);
        sync.write(depth, SyncCommand.Access.DEPTH_ATTACHMENT).nextIs(SyncCommand.Stage.GRAPHICS).run();
        try (var pass = backend.createRenderPass("flywheel:iris/native_water", description, colors, depth,
                viewport.set(depth.getWidth(0), depth.getHeight(0)))) {
            pass.setIndexBuffer(indices, IndexType.SHORT);
            pass.bindPipeline(pipeline);
            pass.enableScissor(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
            pass.drawIndexedIndirect(command.slice(), 1);
        }
    }

    @Override
    public void close() {
        Iris.getRenderBackend().retire(pipeline::close);
        Iris.getRenderBackend().retire(shader::close);
    }
}
