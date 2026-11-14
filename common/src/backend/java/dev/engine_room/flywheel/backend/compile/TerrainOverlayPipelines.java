package dev.engine_room.flywheel.backend.compile;

import com.google.common.hash.Hashing;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.Binding;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.opengl.GlProgram;
#else
import com.mojang.blaze3d.shaders.ShaderType;
#endif
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.compat.backend.PipelineShaderSource;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shader variants retain the receiver's geometry, draw count, alpha rejection and depth state.
 */
public final class TerrainOverlayPipelines {
    public static final int RECEIVER_BINDING = 42;
    public static final int FRAME_BINDING = 43;
    public static final Identifier VERTEX_WRAPPER = ResourceUtil.rl("terrain/overlay_vertex.glsl");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
    private static final Map<Overlay, RenderPipeline> CACHE = new HashMap<>();
    private static final Map<RenderPipeline, Overlay> OVERLAYS = new IdentityHashMap<>();
    private static List<Identifier> requestedSources = List.of();

    private TerrainOverlayPipelines() {
    }

    public static List<Identifier> requestedSources() {
        return requestedSources;
    }

    public static Identifier withSources(Identifier id, List<Identifier> sources) {
        return sources.isEmpty() ? id : id.withPath(id.getPath() + "/terrain_overlay/" + sourceKey(sources));
    }

    private static String sourceKey(List<Identifier> sources) {
        return Hashing.sha256().hashString(sources.toString(), StandardCharsets.UTF_8).toString();
    }

    #if MC >= 26.3
    public static CompiledRenderPipeline nativeCompiled(RenderPipeline base,boolean translucent) {
        if(translucent) {
            var previous=requestedSources;
            requestedSources=TerrainOverlayDraw.sources();
            try {
                var nativeOit=NativeOitScope.select(base);
                if(nativeOit!=null)return nativeOit;
            } finally {requestedSources=previous;}
        }
        return RenderPipelineCompiler.get(nativePipeline(base,translucent));
    }
    #endif

    public static int dataBinding(int index) {
        return 44 + index * 3;
    }

    public static int textureBinding(int index) {
        return 45 + index * 3;
    }

    public static int metadataBinding(int index) {
        return 46 + index * 3;
    }

    public static Integer vulkanBinding(String name) {
        if (name.equals("TerrainReceiverIds")) return RECEIVER_BINDING;
        if (name.equals("TerrainOverlayFrame")) return FRAME_BINDING;
        if (name.startsWith("TerrainOverlayData"))
            return dataBinding(Integer.parseInt(name.substring("TerrainOverlayData".length())));
        if (name.startsWith("TerrainOverlayTexture"))
            return textureBinding(Integer.parseInt(name.substring("TerrainOverlayTexture".length())));
        if (name.startsWith("TerrainOverlayMetadata"))
            return metadataBinding(Integer.parseInt(name.substring("TerrainOverlayMetadata".length())));
        return null;
    }

    public static RenderPipeline nativePipeline(RenderPipeline base) {
        return nativePipeline(base, false);
    }

    public static RenderPipeline nativePipeline(RenderPipeline base, boolean translucent) {
        var overlays = TerrainOverlayDraw.sources();
        if (overlays.isEmpty() || base.getLocation().getPath().startsWith("pipeline/terrain_overlay/")) return base;
        return get(base, overlays, translucent, (_, type) -> {
            #if MC >= 26.3
            var receiverId=base.getShaders().get(type);
            #else
            var receiverId = type == ShaderType.VERTEX ? base.getVertexShader() : base.getFragmentShader();
            #endif
            var compilation = new Compilation();
            compilation.version(ShaderAssembly.glslVersion());
            #if MC >= 26.3
            compilation.define("_FLW_TERRAIN_EXPLICIT_VARYINGS");
            #endif
            if (type == ShaderType.VERTEX) {
                if (!VkContext.isVulkanHost())
                    compilation.define("gl_VertexIndex", "gl_VertexID");
                #if MC >= 26.3
                if(GlPushConstantProgram.supports(base))GlPushConstantProgram.configure(compilation,VkContext.isVulkanHost());
                #endif
            }
            compilation.define("main", type == ShaderType.VERTEX ? "_flw_terrainVertexBase" : "_flw_terrainBase");
            if (type == ShaderType.FRAGMENT && translucent)
                ShaderAssembly.appendComponents(compilation, nativePrelude(overlays,
                        base.getShaderDefines().values().getOrDefault("ALPHA_CUTOUT", "0.0"),
                    #if MC >= 26.3
                    true
                    #else
                    false
                    #endif
            ));
            compilation.appendComponent(new ShaderAssembly.RawSource(receiverId.toString(),
                    VERSION.matcher(nativeSource(receiverId, type)).replaceFirst("")));
            ShaderAssembly.appendComponents(compilation,
                    type == ShaderType.VERTEX ? vertexTail() : translucent ? nativePostlude(true) : tail(overlays));
            return compilation.assembledSource();
        });
    }

    public static RenderPipeline terrainPipeline(boolean cutout) {
        var base = cutout ? TerrainPipelines.cutout() : TerrainPipelines.solid();
        var overlays = TerrainOverlayDraw.sources();
        if (overlays.isEmpty()) return base;
        return get(base, overlays, terrainSource(cutout, overlays));
    }

    /**
     * Guest translucent producer, {@link TerrainPipelines#translucentOit} pass numbering.
     */
    public static RenderPipeline guestOitPipeline(int pass) {
        var base = TerrainPipelines.translucentOit(pass);
        var overlays = TerrainOverlayDraw.sources();
        return overlays.isEmpty() ? base : get(base, overlays, true, terrainSource(false, overlays));
    }

    private static PipelineShaderSource terrainSource(boolean cutout, List<Identifier> overlays) {
        boolean linear = TerrainAtlasFilter.linear();
        return (_, type) -> type == ShaderType.VERTEX
                ? TerrainPipelines.assembleOverlayVertex(ctx -> ctx.requireExtension("GL_ARB_shader_draw_parameters"))
                : TerrainPipelines.assembleOverlay(cutout, linear, overlays);
    }

    /**
     * The registration that produced an overlay variant, or {@code null}.
     */
    public static @Nullable Overlay overlay(RenderPipeline pipeline) {
        return OVERLAYS.isEmpty() ? null : OVERLAYS.get(pipeline);
    }

    private static RenderPipeline get(RenderPipeline base, List<Identifier> overlays, PipelineShaderSource source) {
        return get(base, overlays, false, source);
    }

    public static RenderPipeline producer(RenderPipeline base, PipelineShaderSource source) {
        var overlays = TerrainOverlayDraw.sources();
        return overlays.isEmpty() ? base : get(base, overlays, true, source);
    }

    private static RenderPipeline get(RenderPipeline base, List<Identifier> overlays, boolean translucent,
                                      PipelineShaderSource source) {
        var pipeline = CACHE.computeIfAbsent(new Overlay(base, overlays, translucent), key -> {
            var copy = copy(base, overlays, translucent);
            OVERLAYS.put(copy, key);
            return copy;
        });
        RenderPipelineCompiler.precompile(pipeline, source);
        return pipeline;
    }

    private static RenderPipeline copy(RenderPipeline base, List<Identifier> overlays, boolean translucent) {
        #if MC >= 26.3
        var colors=base.getColorTargetStates().toArray(ColorTargetState[]::new);
        #else
        var colors = base.getColorTargetStates();
        #endif
        var snippet = new RenderPipeline.Snippet(
                #if MC >= 26.3
                base.getShaders(),
                #else
                Optional.of(base.getVertexShader()), Optional.of(base.getFragmentShader()),
                #endif
                Optional.of(base.getShaderDefines()), Optional.of(base.getBindGroupLayouts()), colors, colors.length,
                Optional.of(base.getDepthStencilState()), Optional.of(base.getPolygonMode()),
                Optional.of(base.isCull()),
                #if MC >= 26.3
                base.getVertexFormatBindings().toArray(VertexFormat[]::new),
                #else
                base.getVertexFormatBindings(),
                #endif
                Optional.of(base.getPrimitiveTopology())
                #if MC >= 26.3
                ,base.pushConstantSize()
                #endif
        );
        var digest = sourceKey(overlays);
        var path = digest + "/" + (translucent ? "translucent/" : "opaque/") + base.getLocation()
                                                                                   .getNamespace() + "/" + base.getLocation()
                                                                                                               .getPath();
        var location = ResourceUtil.rl("pipeline/terrain_overlay/" + path);
        #if MC < 26.3
        // Sodium's native Vulkan hooks identify their pipeline contract by namespace.
        location = base.getLocation().withPath(location.getPath());
        #endif
        var builder = RenderPipeline.builder(snippet).withLocation(location)
                                    .withVertexShader(ResourceUtil.rl("codegen/terrain_overlay/vertex/" + path))
                                    .withFragmentShader(ResourceUtil.rl("codegen/terrain_overlay/fragment/" + path));
        for (var layout : layouts(overlays)) builder.withBindGroupLayout(layout);
        #if MC >= 26.3
        return new OverlayPipeline(builder.build(),GlPushConstantProgram.supports(base));
        #else
        return builder.build();
        #endif
    }

    public static List<BindGroupLayout> layouts(List<Identifier> overlays) {
        if (overlays.isEmpty()) return List.of();
        var result = new ArrayList<BindGroupLayout>();
        result.add(BindGroupLayout.builder()
                                  .withUniform("TerrainReceiverIds", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                                  .withUniform("TerrainOverlayFrame", UniformType.UNIFORM_BUFFER)
                                  .withImageSampler("TerrainOverlayLightmap").build());
        for (int index = 0; index < overlays.size(); index++)
            result.add(BindGroupLayout.builder()
                                      .withUniform("TerrainOverlayData" + index, UniformType.UNIFORM_BUFFER)
                                      .withImageSampler("TerrainOverlayTexture" + index)
                                      .withImageSampler("TerrainOverlayMetadata" + index).build());
        return result;
    }

    public static void vulkanLayouts(List<Binding> bindings,
                                     List<Identifier> overlays, int geometryStage) {
        if (overlays.isEmpty()) return;
        int fragment = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
        bindings.removeIf(binding -> binding.binding() == 12);
        bindings.add(new Binding(12,
                VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, geometryStage | fragment));
        bindings.add(new Binding(FRAME_BINDING,
                VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, geometryStage));
        for (int index = 0; index < overlays.size(); index++) {
            bindings.add(new Binding(dataBinding(index), VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fragment));
            bindings.add(new Binding(textureBinding(index), VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, fragment));
            bindings.add(new Binding(metadataBinding(index), VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, fragment));
        }
    }

    #if MC >= 26.3
    private static final class OverlayPipeline extends RenderPipeline implements GlPipelineProgram {
        private final boolean sodiumPushConstants;
        private OverlayPipeline(RenderPipeline base,boolean sodiumPushConstants) {
            super(base.getLocation(),base.getShaders(),base.getShaderDefines(),base.getBindGroupLayouts(),
                    base.getColorTargetStates().toArray(ColorTargetState[]::new),base.getDepthStencilState(),base.getPolygonMode(),base.isCull(),
                    base.getVertexFormatBindings().toArray(VertexFormat[]::new),base.getPrimitiveTopology(),base.pushConstantSize(),base.getSortKey());
            this.sodiumPushConstants=sodiumPushConstants;
        }
        @Override public boolean supportsGlPushConstants() {return sodiumPushConstants;}
        @Override public GlProgram createGlProgram(int handle) {
            return sodiumPushConstants?new GlPushConstantProgram(handle,getLocation().toString()):new GlProgram(handle,getLocation().toString());
        }
    }
    #endif

    public static List<SourceComponent> vertexTail() {
        return List.of(FlwPrograms.SOURCES.get(VERTEX_WRAPPER));
    }

    public static List<SourceComponent> tail(List<Identifier> overlays) {
        var result = new ArrayList<>(prelude(overlays, "0.0"));
        result.add(new ShaderAssembly.RawSource("terrain_overlay_main",
                "#undef main\nvoid main() {\n    _flw_terrainPrepare();\n    _flw_terrainBase();\n    fragColor=_flw_terrainShadeOpaque(fragColor);\n}\n"));
        return result;
    }

    public static List<SourceComponent> prelude(List<Identifier> overlays, String cutoff) {
        var result = new ArrayList<SourceComponent>();
        result.add(new ShaderAssembly.RawSource("terrain_overlay_header", """
                #ifdef _FLW_TERRAIN_EXPLICIT_VARYINGS
                #ifndef _FLW_TERRAIN_BLOCK_INPUT
                layout(location=6) in vec3 _flw_terrainPosition;
                layout(location=7) flat in uint _flw_terrainReceiver;
                #endif
                #else
                in vec3 _flw_terrainPosition;
                flat in uint _flw_terrainReceiver;
                #endif
                uniform sampler2D TerrainOverlayLightmap;
                """));
        for (int index = 0; index < overlays.size(); index++) {
            result.add(new ShaderAssembly.RawSource("terrain_overlay_scope_" + index,
                    "#define TerrainOverlayData TerrainOverlayData" + index + "\n#define TerrainOverlayTexture TerrainOverlayTexture" + index
                            + "\n#define TerrainOverlayMetadata TerrainOverlayMetadata" + index + "\n#define flw_terrainOverlay _flw_terrainOverlay" + index
                            + "\nuniform sampler2D TerrainOverlayTexture;\nuniform sampler2D TerrainOverlayMetadata;\n"));
            result.add(FlwPrograms.SOURCES.get(overlays.get(index)));
            result.add(new ShaderAssembly.RawSource("terrain_overlay_scope_end_" + index,
                    "#undef TerrainOverlayData\n#undef TerrainOverlayTexture\n#undef TerrainOverlayMetadata\n#undef flw_terrainOverlay\n"));
        }
        var helpers = new StringBuilder();
        for (int index = 0; index < overlays.size(); index++)
            helpers.append("vec4 _flw_terrainLayer").append(index).append(";\n");
        helpers.append(
                "void _flw_terrainPrepare() {\n    vec3 normal=normalize(cross(dFdx(_flw_terrainPosition),dFdy(_flw_terrainPosition)));\n    if(dot(normal,_flw_terrainPosition)>0.0)normal=-normal;\n    if(!gl_FrontFacing)normal=-normal;\n");
        for (int index = 0; index < overlays.size(); index++)
            helpers.append("    _flw_terrainLayer").append(index).append("=_flw_terrainOverlay").append(index)
                   .append("(_flw_terrainPosition,normal,_flw_terrainReceiver);\n");
        helpers.append("}\nfloat _flw_terrainAlpha(float alpha) {\n    if(alpha<").append(cutoff)
               .append(")alpha=0.0;\n");
        for (int index = 0; index < overlays.size(); index++)
            helpers.append("    alpha=alpha*(1.0-_flw_terrainLayer").append(index).append(".a)+_flw_terrainLayer")
                   .append(index).append(".a;\n");
        helpers.append("    return alpha;\n}\nvec4 _flw_terrainShadeOpaque(vec4 color) {\n");
        for (int index = 0; index < overlays.size(); index++)
            helpers.append("    color.rgb=color.rgb*(1.0-_flw_terrainLayer").append(index)
                   .append(".a)+_flw_terrainLayer").append(index).append(".rgb;\n");
        helpers.append("    return color;\n}\nvec4 _flw_terrainShadePremul(vec4 color) {\n");
        for (int index = 0; index < overlays.size(); index++)
            helpers.append("    color=color*(1.0-_flw_terrainLayer").append(index).append(".a)+_flw_terrainLayer")
                   .append(index).append(";\n");
        helpers.append("    return color;\n}\nvec4 _flw_terrainPremultiply(vec4 color) {\n    if(color.a<")
               .append(cutoff)
               .append(")return vec4(0.0);\n    return vec4(color.rgb*color.a,color.a);\n}\nvec4 _flw_terrainStraight(vec4 color) {\n    return color.a>0.0?vec4(color.rgb/color.a,color.a):vec4(0.0);\n}\nvec4 _flw_terrainShade(vec4 color) {\n    return _flw_terrainStraight(_flw_terrainShadePremul(_flw_terrainPremultiply(color)));\n}\n");
        result.add(new ShaderAssembly.RawSource("terrain_overlay_helpers", helpers.toString()));
        return result;
    }

    public static List<SourceComponent> nativePrelude(List<Identifier> overlays, String cutoff,
                                                      boolean nativeOitLibrary) {
        var result = new ArrayList<SourceComponent>();
        if (nativeOitLibrary) {
            result.add(new ShaderAssembly.RawSource("terrain_native_oit_library", "#include <minecraft:oit.glsl>\n"));
        }
        result.addAll(prelude(overlays, cutoff));
        result.add(new ShaderAssembly.RawSource("terrain_native_oit_hooks", """
                #ifdef OIT_ALPHA_ONLY
                void _flw_terrainAlphaPhase(float depth,float alpha) {
                    float composed=_flw_terrainAlpha(alpha);
                    #ifdef OIT_DEPTH_BOUNDS
                    // Depth preparation records a footprint and an opaque flag, not continuous opacity.
                    if(composed>0.0)composed=max(composed,0.01);
                    #endif
                    executeAlphaOnlyPhase(depth,composed);
                }
                #define executeAlphaOnlyPhase _flw_terrainAlphaPhase
                #endif
                #ifdef OIT_ACCUMULATE
                vec4 _flw_terrainDeferAccumulation(vec4 color) {return _flw_terrainPremultiply(color);}
                #define sampleColorForAccumulation _flw_terrainDeferAccumulation
                #endif
                #ifdef ALPHA_CUTOUT
                #undef ALPHA_CUTOUT
                #define ALPHA_CUTOUT (_flw_terrainAlpha(0.0)>0.0?0.0:(%s))
                #endif
                """.formatted(cutoff)));
        return result;
    }

    public static List<SourceComponent> nativePostlude(boolean main) {
        var result = new ArrayList<SourceComponent>();
        result.add(new ShaderAssembly.RawSource("terrain_native_oit_finish", """
                #undef executeAlphaOnlyPhase
                #undef sampleColorForAccumulation
                #ifndef OIT_ALPHA_ONLY
                vec4 _flw_terrainFinish(vec4 color) {
                    #ifdef OIT_ACCUMULATE
                    return sampleColorForAccumulation(_flw_terrainStraight(_flw_terrainShadePremul(color)));
                    #else
                    return _flw_terrainShade(color);
                    #endif
                }
                #endif
                """));
        if (main) result.add(new ShaderAssembly.RawSource("terrain_native_oit_main", """
                #undef main
                void main() {
                    _flw_terrainPrepare();
                    _flw_terrainBase();
                    #ifndef OIT_ALPHA_ONLY
                    fragColor=_flw_terrainFinish(fragColor);
                    #endif
                }
                """));
        return result;
    }


    private static String nativeSource(Identifier id, ShaderType type) {
        #if MC < 26.3
        return Minecraft.getInstance().getShaderManager().getShader(id, type);
        #else
        var file=id.withPath("shaders/"+id.getPath()+(type==ShaderType.VERTEX?".vsh":".fsh"));
        try(var stream=Minecraft.getInstance().getResourceManager().getResource(file).orElseThrow().open()) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException failure) {throw new IllegalStateException("Could not read terrain shader "+file,failure);}
        #endif
    }

    public static void clear() {
        CACHE.clear();
        OVERLAYS.clear();
    }

    public record Overlay(RenderPipeline base, List<Identifier> sources, boolean translucent) {
    }
}
