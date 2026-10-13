package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.engine.ExperimentalReplayProgram;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.targets.RenderFramebuffer;
import net.irisshaders.iris.targets.WorldRenderTargets.Phase;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline-owned Colorwheel wavelet OIT with 2D coefficient attachments.
 */
public final class ExperimentalOit implements AutoCloseable {
    private static final ExperimentalOit[] CACHED = new ExperimentalOit[2];
    private static final boolean[] CHECKED = new boolean[2];
    private static IrisRenderingPipeline owner;
    private final IrisRenderingPipeline parent;
    private final Plan plan;
    private final List<GpuTextureView> coefficients = new ArrayList<>();
    private final List<GpuTextureView> accumulate = new ArrayList<>();
    private final Map<String, GpuTextureView> textures = new HashMap<>();
    private final CompositeProgram composite;
    private final RenderFramebuffer output;
    private final ExperimentalReplayProgram depthFill;
    private final GpuBuffer indices;
    private final GpuBuffer command;
    private GpuTextureView range;
    private int width, height;

    private ExperimentalOit(IrisRenderingPipeline parent, Plan plan) {
        this.parent = parent;
        this.plan = plan;
        String name = "flywheel_wavelet_composite_" + plan.shadow();
        var access = parent;
        var phase = plan.shadow() ? Phase.SHADOW : Phase.TRANSLUCENT;
        var attachments = new ColorAttachmentPlan.Builder().program(name, plan.buffers(),
                                                                   plan.shadow() ? parent.getFlippedBeforeShadow() : parent.getWorldTargets().getFlippedBuffers(phase))
                                                           .build(RenderSystem.getDevice().getDeviceInfo().limits()
                                                                              .maxColorAttachments());
        var outputs = attachments.program(name);
        output = plan.shadow() ? ((IrisRenderingPipelineAccessor) access).flywheel$shadowTargets().get()
                                                                         .createMergedFramebuffer(attachments)
                : ((IrisRenderingPipelineAccessor) access).flywheel$renderTargets()
                                                          .createMergedFramebuffer(attachments, true);
        var contract = ContractProgram.byName(plan.source().getName());
        BlendModeOverride override = plan.properties().blend(contract);
        BlendMode blend = override == null ? plan.shadow() ? null : new BlendMode(GL11C.GL_SRC_ALPHA,
                GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA) : override.blendMode();
        var blends = new ArrayList<>(Collections.nCopies(plan.buffers().length, blend));
        for (var information : plan.properties().bufferBlend(contract))
            for (int i = 0; i < plan.buffers().length; i++)
                if (plan.buffers()[i] == information.index()) blends.set(i, information.blendMode());
        String vertex = ShaderAssembly.assemble(ShaderAssembly.NO_EXTRA,
                List.of(new ShaderAssembly.RawSource("wavelet fullscreen quad", """
                        void main() {
                            vec2 corner = vec2(gl_VertexID == 1 || gl_VertexID == 2 ? 1.0 : 0.0, gl_VertexID >= 2 ? 1.0 : 0.0);
                            gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                        }
                        """)));
        String fragment = GuestOitCodegen.compositeFragment(spec(null));
        var sources = Map.of(PatchShaderType.VERTEX, NativeShaderPreprocessor.preprocessKind(vertex, name,
                        Shaderc.shaderc_vertex_shader, Map.of()), PatchShaderType.FRAGMENT,
                NativeShaderPreprocessor.preprocessKind(fragment, name, Shaderc.shaderc_fragment_shader, Map.of()));
        composite = new CompositeProgram(parent.getPackSamplers(), name, sources, outputs, outputs.remapBlends(blends));
        var resources = composite.getSamplers();
        for (String sampler : samplerNames())
            resources.addDrawTextureSampler(new TextureBinding("flywheel/oit/" + sampler,
                    () -> textures.get(sampler), () -> GlSampler.NEAREST), false, sampler);
        resources.finish();
        depthFill = new ExperimentalReplayProgram(parent, "native_oit_depth.frag", false, true, compilation -> {
            if (plan.shadow()) compilation.define("FLW_OIT_SHADOW_DEPTH");
        }, Set.of(), plan.shadow() ? CompareOp.LESS_THAN_OR_EQUAL
                : CompareOp.GREATER_THAN_OR_EQUAL);
        ByteBuffer indexData = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        indexData.asShortBuffer().put(new short[]{0, 1, 2, 2, 3, 0});
        indices = RenderSystem.getDevice()
                              .createBuffer(() -> "flywheel wavelet quad", GpuBuffer.USAGE_INDEX, indexData);
        command = Iris.getRenderBackend().createStorageBuffer("flywheel wavelet depth command", 20, null);
        ByteBuffer commandData = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        commandData.putInt(6).putInt(1).putInt(0).putInt(0).putInt(0).flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(command.slice(), commandData);
    }

    public static ExperimentalOit get(IrisRenderingPipeline parent, boolean shadow) {
        if (owner != parent) {
            release();
            owner = parent;
        }
        int index = shadow ? 1 : 0;
        if (!CHECKED[index]) {
            var plan = find(parent, shadow);
            if (plan != null) CACHED[index] = new ExperimentalOit(parent, plan);
            CHECKED[index] = true;
        }
        return CACHED[index];
    }

    /**
     * Register active dimension plans on the render thread before Iris seals its sampler heap.
     */
    public static void registerSamplers(IrisRenderingPipeline parent) {
        for (boolean shadow : new boolean[]{false, true}) {
            var plan = find(parent, shadow);
            if (plan == null) continue;
            var pack = parent.getPackSamplers();
            pack.register(SamplerHeapLayout.Type.SAMPLER2D, "flywheel/oit/_flw_depthRange", true);
            pack.register(SamplerHeapLayout.Type.SAMPLER2D, "flywheel/oit/_flw_blueNoise", true);
            var ranks = plan.properties().oit(shadow).ranks();
            for (int set = 0; set < ranks.length; set++)
                for (int layer = 0; layer < GuestOitCodegen.layers(ranks[set]); layer++)
                    pack.register(SamplerHeapLayout.Type.SAMPLER2D,
                            "flywheel/oit/_flw_coefficients" + set + '_' + layer, true);
            for (int slot = 0; slot < plan.buffers().length; slot++)
                pack.register(SamplerHeapLayout.Type.SAMPLER2D, "flywheel/oit/_flw_accumulate" + slot, true);
        }
    }

    private static Plan find(IrisRenderingPipeline parent, boolean shadow) {
        var properties = GuestProgramResolver.oitProperties(parent, shadow);
        if (properties == null || !properties.oit(shadow).enabled()) return null;
        var access = parent;
        var programs = ((ProgramFallbackResolverAccessor) ((IrisRenderingPipelineAccessor) access).flywheel$resolver()).flywheel$programs();
        var source = programs.flywheel$contractSource(
                shadow ? ContractProgram.SHADOW_TRANSLUCENT : ContractProgram.GBUFFERS_TRANSLUCENT);
        if (source == null || source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent()
                || source.getTessEvalSource().isPresent()) return null;
        int[] buffers = shadow && source.getDirectives()
                                        .hasUnknownDrawBuffers() ? new int[]{0, 1} : source.getDirectives()
                                                                                           .getDrawBuffers();
        int count = 0;
        for (int rank : properties.oit(shadow).ranks()) count += GuestOitCodegen.layers(rank);
        int maximum = RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments();
        if (count > maximum || buffers.length > maximum || !blendable(GpuFormat.RGBA32_FLOAT)
                || count != 0 && !blendable(GpuFormat.RGBA16_FLOAT)) {
            FlwBackend.LOGGER.info("Forward OIT unavailable for {}: coefficient attachments or blending unsupported",
                    source.getName());
            return null;
        }
        for (int buffer : buffers)
            if (!blendable(properties.oit(shadow).accumulate(buffer).format().toGpuFormat())) {
                FlwBackend.LOGGER.info("Forward OIT unavailable for {}: accumulator blending unsupported for target {}",
                        source.getName(), buffer);
                return null;
            }
        return new Plan(properties, source, buffers, shadow);
    }

    private static boolean blendable(GpuFormat format) {
        if (VkContext.isVulkanHost()) {
            try (var stack = MemoryStack.stackPush()) {
                var properties = VkFormatProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceFormatProperties(VkContext.device().vkDevice().getPhysicalDevice(),
                        VulkanConst.toVk(format), properties);
                return (properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0;
            }
        }
        var caps = GlCompat.CAPABILITIES;
        if (!(caps.OpenGL43 || caps.GL_ARB_internalformat_query2)) return false;
        return GL42C.glGetInternalformati(GL11C.GL_TEXTURE_2D, GlConst.toGlInternalId(format),
                GL43C.GL_FRAMEBUFFER_BLEND) != GL11C.GL_NONE;
    }

    private static void retire(GpuTextureView view) {
        var texture = view.texture();
        Iris.getRenderBackend().retire(() -> {
            view.close();
            texture.close();
        });
    }

    public static void release(IrisRenderingPipeline pipeline) {
        if (owner == pipeline) release();
    }

    private static void release() {
        for (int i = 0; i < CACHED.length; i++) {
            if (CACHED[i] != null) CACHED[i].close();
            CACHED[i] = null;
            CHECKED[i] = false;
        }
        owner = null;
    }

    GuestShaders.OitSpec spec(OitPass pass) {
        return new GuestShaders.OitSpec(pass, plan.properties().oit(plan.shadow()), plan.buffers(), plan.shadow());
    }

    private List<String> samplerNames() {
        var names = new ArrayList<>(List.of("_flw_depthRange", "_flw_blueNoise"));
        var ranks = plan.properties().oit(plan.shadow()).ranks();
        for (int set = 0; set < ranks.length; set++)
            for (int layer = 0; layer < GuestOitCodegen.layers(ranks[set]); layer++)
                names.add("_flw_coefficients" + set + '_' + layer);
        for (int slot = 0; slot < plan.buffers().length; slot++) names.add("_flw_accumulate" + slot);
        return names;
    }

    public boolean hasCoefficients() {
        return plan.properties().oit(plan.shadow()).ranks().length != 0;
    }

    public void prepare() {
        var depth = depth();
        if (width != depth.getWidth(0) || height != depth.getHeight(0)) {
            releaseTargets();
            width = depth.getWidth(0);
            height = depth.getHeight(0);
            range = texture(GpuFormat.RGBA32_FLOAT);
            textures.put("_flw_depthRange", range);
            var ranks = plan.properties().oit(plan.shadow()).ranks();
            for (int set = 0; set < ranks.length; set++)
                for (int layer = 0; layer < GuestOitCodegen.layers(ranks[set]); layer++) {
                    var view = texture(GpuFormat.RGBA16_FLOAT);
                    coefficients.add(view);
                    textures.put("_flw_coefficients" + set + '_' + layer, view);
                }
            for (int slot = 0; slot < plan.buffers().length; slot++) {
                var view = texture(
                        plan.properties().oit(plan.shadow()).accumulate(plan.buffers()[slot]).format().toGpuFormat());
                accumulate.add(view);
                textures.put("_flw_accumulate" + slot, view);
            }
        }
        textures.put("_flw_blueNoise", NoiseTextures.BLUE_NOISE.getTextureView());
        Iris.getRenderBackend().commands().clearColors(List.of(range), new Vector4f(-1e30f, -1e30f, 0, 0));
        if (!coefficients.isEmpty())
            Iris.getRenderBackend().commands().clearColors(coefficients, new Vector4f(0, 0, 0, 0));
        Iris.getRenderBackend().commands().clearColors(accumulate, new Vector4f(0, 0, 0, 0));
    }

    GpuTextureView depth() {
        var access = parent;
        return plan.shadow() ? ((IrisRenderingPipelineAccessor) access).flywheel$shadowTargets().get()
                                                                       .getDepthTextureView(
                                                                               0) : ((IrisRenderingPipelineAccessor) access).flywheel$renderTargets()
                                                                                                                            .getDepthTextureView(
                                                                                                                                    0);
    }

    List<GpuTextureView> colors(OitPass pass) {
        return switch (pass) {
            case DEPTH_RANGE -> List.of(range);
            case COEFFICIENTS -> coefficients;
            case EVALUATE -> accumulate;
        };
    }

    GpuTextureView texture(String name) {
        return textures.get(name);
    }

    public void composite() {
        composite.draw(output.getColorViews(), new ViewportData(1, 0, 0), Minecraft.getInstance().level.getGameTime(),
                SystemTimeUniforms.COUNTER.getAsInt());
        depthFill.draw(List.of(), depth(), Map.of("_flw_depthRange", range), indices, command.slice(), 0);
    }

    private GpuTextureView texture(GpuFormat format) {
        var texture = RenderSystem.getDevice()
                                  .createTexture("flywheel wavelet target", GpuTexture.USAGE_RENDER_ATTACHMENT
                                                  | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                                          format, width, height, 1, 1);
        return RenderSystem.getDevice().createTextureView(texture);
    }

    private void releaseTargets() {
        if (range != null) retire(range);
        coefficients.forEach(ExperimentalOit::retire);
        coefficients.clear();
        accumulate.forEach(ExperimentalOit::retire);
        accumulate.clear();
        textures.clear();
        range = null;
        width = height = 0;
    }

    @Override
    public void close() {
        releaseTargets();
        composite.destroy();
        depthFill.close();
        Iris.getRenderBackend().retire(indices::close);
        Iris.getRenderBackend().retire(command::close);
    }

    private record Plan(ContractProperties properties, ProgramSource source, int[] buffers, boolean shadow) {
    }
}
