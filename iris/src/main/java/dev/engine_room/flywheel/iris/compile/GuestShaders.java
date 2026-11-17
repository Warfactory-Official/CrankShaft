package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.component.BufferTextureInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.UberInstanceComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.compile.patches.OrderedBillboardCapture;
import dev.engine_room.flywheel.iris.compile.patches.SortedBillboardBlend;
import dev.engine_room.flywheel.iris.compile.patches.GuestLodOcclusion;
import dev.engine_room.flywheel.iris.compile.patches.SundialTranslucent;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.GroupingExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.NegationExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.*;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
#if MC >= 26.3
import net.irisshaders.iris.shaderpack.texture.TextureStage;
#endif
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Render-thread transforms; resource warmup clears memos.
 */
public final class GuestShaders {
    static final String[] ATTRIBUTES = {"_flw_aPosition", "_flw_aColor", "_flw_aTexCoord", "_flw_aOverlay",
            "_flw_aLight", "_flw_aNormal", "_flw_aIrisEntity", "_flw_aMidTexCoord", "_flw_aTangent", "_flw_aMidBlock",
            "_flw_aFaceNormal"};

    private static final Identifier HEADER = ResourceUtil.rl("iris/guest_header.vert");
    private static final Identifier VERTEX_LIGHT_HEADER = ResourceUtil.rl("iris/guest_vertex_light_header.vert");
    private static final Identifier VERTEX_LIGHT = ResourceUtil.rl("iris/guest_vertex_light.vert");
    private static final Identifier INSTANCING = ResourceUtil.rl("iris/guest_instancing.vert");
    private static final Identifier INDIRECT = ResourceUtil.rl("iris/guest_indirect.vert");
    private static final Identifier SORTED_BLEND_VERTEX = ResourceUtil.rl("iris/sorted_billboard_gate.vert");
    private static final Identifier DRAW_COMMAND = ResourceUtil.rl("internal/indirect/draw_command.glsl");
    private static final Identifier MATRICES = ResourceUtil.rl("internal/indirect/matrices.glsl");
    private static final Identifier CONTRACT_HEADER = ResourceUtil.rl("iris/guest_contract_header.frag");
    private static final Identifier CONTRACT = ResourceUtil.rl("iris/guest_contract.frag");
    private static final Identifier CONTRACT_GEOMETRY = ResourceUtil.rl("iris/guest_contract_geom.glsl");
    private static final Identifier DIFFUSE = ResourceUtil.rl("internal/diffuse.glsl");
    private static final Identifier INSTANCING_LIGHT = ResourceUtil.rl("internal/instancing/light.glsl");
    private static final Identifier INDIRECT_LIGHT = ResourceUtil.rl("internal/indirect/light.glsl");
    private static final String CONTRACT_PROTOTYPE = "void clrwl_computeFragment(vec4 sampleColor, out vec4 fragColor, "
            + "out vec2 fragLight, out float ao, out vec4 fragOverlay);\nbool _flw_isUnlit();\n"
            + "float _flw_unlitCardinalFactor();\nbool _flw_hasFog();\nfloat _flw_emissionCoverage();\nbool _flw_borrowedBlock();\n"
            // Colorwheel's fragment vertex inputs: pack code precedes the library's ClrwlVertexData block.
            + "vec4 _flw_clrwlVertexPos();\nvec4 _flw_clrwlVertexColor();\nvec2 _flw_clrwlVertexTexCoord();\n"
            + "ivec2 _flw_clrwlVertexOverlay();\nvec2 _flw_clrwlVertexLight();\nvec3 _flw_clrwlVertexNormal();\n"
            + "#define clrwl_vertexPos _flw_clrwlVertexPos()\n#define clrwl_vertexColor _flw_clrwlVertexColor()\n"
            + "#define clrwl_vertexTexCoord _flw_clrwlVertexTexCoord()\n#define clrwl_vertexOverlay _flw_clrwlVertexOverlay()\n"
            + "#define clrwl_vertexLight _flw_clrwlVertexLight()\n#define clrwl_vertexNormal _flw_clrwlVertexNormal()\n";
    // Super Resolution's Iris extension: this frame's minus the previous frame's view position.
    static final String VELOCITY = "at_velocity";
    // Entity shadows recycle their block-aligned quads across blocks: no motion of their own.
    static final Set<InstanceType<?>> STATIC_GEOMETRY = Set.of(InstanceTypes.SHADOW);
    private static final Pattern LEADING_DIRECTIVE = Pattern.compile(
            "[ \\t]*(?:#(?:extension|pragma)[^\\n]*|//[^\\n]*)?\\n");
    private static final Pattern STORAGE_BLOCK = Pattern.compile("\\bbuffer\\b");

    private static final Map<String, String> INPUT_SOURCES = Map.ofEntries(
            Map.entry("iris_Position", "vec4(flw_vertexPos.xyz, 1.0)"),
            Map.entry("iris_Color", "_flw_guestColor()"),
            Map.entry("iris_Normal", "vec4(_flw_guestIrisNormal(), 0.0)"),
            Map.entry("iris_UV0", "vec4(flw_vertexTexCoord, 0.0, 1.0)"),
            Map.entry("iris_UV1", "ivec4(flw_vertexOverlay, 0, 0)"),
            Map.entry("iris_UV2", "vec4(round(flw_vertexLight * 256.0), 0.0, 1.0)"),
            Map.entry("iris_Entity", "_flw_guestIrisEntity()"),
            Map.entry("iris_LineWidth", "vec4(1.0)"),
            Map.entry("mc_Entity", "ivec4(_flw_guestMcEntity(), 0, 1)"),
            Map.entry("mc_midTexCoord", "vec4(_flw_irisMidTexCoord, 0.0, 1.0)"),
            Map.entry("at_tangent", "_flw_guestTangent()"),
            Map.entry("at_midBlock", "_flw_irisMidBlock"),
            Map.entry(VELOCITY, "_flw_guestVelocity"));
    private static final List<String> PROXY_INPUTS = List.of("mc_midTexCoord", "at_tangent", "at_midBlock");
    static final Set<String> ALBEDO = Set.of("tex", "texture", "gtexture", "u_MainSampler");
    static final Set<String> ALBEDO_SAMPLES = Set.of("texture", "textureLod", "textureGrad", "textureProj",
            "textureProjLod", "textureProjGrad", "texelFetch", "texelFetchOffset", "textureOffset", "textureLodOffset",
            "textureGradOffset", "textureProjOffset", "textureProjLodOffset", "textureProjGradOffset");
    private static final String ALBEDO_SOURCE = "sampler2D(_flw_textureHandles[_flw_guestTexSlot])";
    private static final Map<String, String> ALBEDO_UNIFORMS = Map.of(
            "atlasSize", "_flw_guestTextureInfo[_flw_guestTexSlot * 2u].xy",
            "gtextureSize", "_flw_guestTextureInfo[_flw_guestTexSlot * 2u].zw",
            "gtextureId", "_flw_guestTextureInfo[_flw_guestTexSlot * 2u + 1u].x");

    private static final ShaderAttributeInputs INPUTS = new ShaderAttributeInputs(IrisVertexFormats.ENTITY, false,
            false, false, false, false);
    private static final ShaderAttributeInputs GLINT_INPUTS = new ShaderAttributeInputs(
            DefaultVertexFormat.POSITION_TEX,
            false, false, true, false, false);

    private static final Pattern VERSION_LINE = Pattern.compile("#version\\s+(\\d+)[^\\n]*\\n");
    private static final Pattern EARLY_CAPTURE = Pattern.compile(
            "layout\\s*\\(\\s*early_fragment_tests\\s*\\)\\s*in\\s*;");
    private static final Pattern CAPTURE_TEST = Pattern.compile(
            "const\\s+bool\\s+flw_captureTest\\s*=\\s*false\\s*;");
    private static final Set<String> LATE_CAPTURES = ConcurrentHashMap.newKeySet();
    private static final Pattern ALPHA_DITHER_DISCARD = Pattern.compile(
            "(?m)^[ \\t]*if\\s*\\(\\s*[A-Za-z_]\\w*\\.a\\s*<[^;\\r\\n]*\\bdither\\b[^;\\r\\n]*\\)\\s*discard\\s*;[ \\t]*$");

    private static final SingleASTTransformer<JobParameters> TRANSFORMER = versionedTransformer();
    private static final SingleASTTransformer<JobParameters> FRAGMENT_TRANSFORMER = versionedTransformer();
    private static final Map<TextPair, String> ADAPTED = new HashMap<>();
    private static final Map<String, String> CHUNK_FADE = new HashMap<>();
    private static final Map<String, String> SHIFTED = new HashMap<>();
    private static final Map<String, Rewritten> REWRITTEN = new HashMap<>();
    private static final Map<TextPair, Bindless> BINDLESS = new HashMap<>();
    private static final Map<TextPair, String> CUTOUT = new HashMap<>();
    private static final Map<String, OitFragment> DEMOTED = new HashMap<>();
    private static @Nullable VertexInterface lastInterface;
    private static @Nullable Map<Integer, String> lastOutputs;

    static {
        TRANSFORMER.setTransformation((TranslationUnit tree, Root root) -> lastInterface = rewriteInputs(tree, root));
        FRAGMENT_TRANSFORMER.setTransformation(
                (TranslationUnit tree, Root root) -> lastOutputs = demoteOutputs(tree, root));
    }

    private GuestShaders() {
    }

    static GlslVersion glslVersion() {
        return VkContext.isVulkanHost() ? GlslVersion.V460 : GlCompat.MAX_GLSL_VERSION;
    }

    static String versionPreamble() {
        Compilation ctx = new Compilation();
        ctx.version(glslVersion());
        if (IrisRuntime.EXPERIMENTAL) ctx.define("_FLW_GUEST_FLAT_COEFFICIENTS");
        return ctx.assembledSource();
    }

    static String libraryPreamble() {
        Compilation ctx = new Compilation();
        if (!VkContext.isVulkanHost()) ctx.polyfillFmaIfMissing();
        return ctx.assembledSource();
    }

    static void requireSupportedStage(String label, @Nullable String stage) {
        if (stage == null) return;
        Matcher version = VERSION_LINE.matcher(stage);
        if (!version.find()) throw new IllegalStateException(label + " without #version");
        int required = Integer.parseInt(version.group(1));
        if (required > glslVersion().version) {
            throw new BackendUnavailableException(label + " needs GLSL " + required + "; available " + glslVersion());
        }
    }

    public static SingleASTTransformer<JobParameters> versionedTransformer() {
        SingleASTTransformer<JobParameters> transformer = new SingleASTTransformer<>() {
            @Override
            public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
                Matcher matcher = VERSION_LINE.matcher(input);
                if (!matcher.find()) {
                    throw new IllegalStateException("Iris output without #version");
                }
                getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));
                return super.parseTranslationUnit(rootInstance, input);
            }
        };
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        transformer.setPrintType(PrintType.SIMPLE);
        return transformer;
    }

    /**
     * How a pipeline drawing {@code source}'s guest light capture tests depth. Under early fragment tests ({@code
     * DeferredSources.earlyFragmentTests}) the live depth holds all the snapshot does, so the in-shader snapshot test
     * ({@code flw_captureTest}) compiles out. A pipeline writing depth through a program that may discard keeps late
     * tests (early ones would write that depth for discarded fragments too) and tests the seam's snapshot ({@code
     * CaptureSnapshot}) in the shader; so does one with an always-passing depth test.
     */
    enum Capture {
        EARLY,
        SNAPSHOT,
        TESTED,
        LATE
    }

    static boolean capturesLight(ProgramSource source) {
        return source.getFragmentSource().orElseThrow().contains("flw_captureLight(");
    }

    static Capture capture(ProgramSource source, ProgramKey key, boolean depthWrite, boolean depthTested,
                           String pipeline) {
        if (!capturesLight(source)) return Capture.EARLY;
        String fragment = source.getFragmentSource().orElseThrow();
        boolean early = EARLY_CAPTURE.matcher(fragment).find();
        if (early && depthWrite && (key.cutout() != CutoutShaders.OFF || fragment.contains("discard"))) {
            if (LATE_CAPTURES.add(pipeline)) {
                FlwBackend.LOGGER.debug("{} writes depth and may discard; {} captures light under late depth tests",
                        pipeline, source.getName());
            }
            return Capture.LATE;
        }
        return early && depthTested ? Capture.EARLY : Capture.TESTED;
    }

    /**
     * {@code contract}: Colorwheel {@code clrwl_computeFragment} interface.
     */
    /**
     * {@code glyph}: R8 font coverage sampled as white RGB, red alpha (GuestGlyphAlbedo).
     */
    static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                        ProgramKey key, boolean contract, @Nullable OitSpec oit, boolean glyph, Capture capture) {
        return build(pipeline, source, alpha, key, contract, oit, false, glyph, capture);
    }

    static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                        ProgramKey key, boolean contract, @Nullable OitSpec oit, boolean visibleInstances,
                        boolean glyph, Capture capture) {
        return build(pipeline, source, alpha, key, contract, oit, visibleInstances, glyph, capture, false);
    }

    static Stages buildStaticShadow(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                                    ProgramKey key, boolean contract) {
        return build(pipeline, source, alpha, key, contract, null, false, false, Capture.EARLY, true);
    }

    private static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                                ProgramKey key, boolean contract, @Nullable OitSpec oit, boolean visibleInstances,
                                boolean glyph, Capture capture, boolean staticGeometry) {
        String packFragment = source.getFragmentSource().orElseThrow();
        if (!IrisRuntime.EXPERIMENTAL)
            packFragment = packFragment.replaceAll("\\bconst\\s+int\\s+flw_lightPhase\\s*=\\s*0\\s*;",
                    "uniform int flw_lightPhase;");
        if (capture == Capture.LATE) packFragment = EARLY_CAPTURE.matcher(packFragment).replaceAll("");
        if (capture == Capture.TESTED || capture == Capture.LATE)
            packFragment = CAPTURE_TEST.matcher(packFragment).replaceAll("const bool flw_captureTest = true;");
        if (key.role() == PackRole.ADDITIVE)
            packFragment = ALPHA_DITHER_DISCARD.matcher(packFragment).replaceAll("");
        String packVertex = source.getVertexSource().orElseThrow();
        boolean adapted = packFragment.contains(ContractPatches.NATIVE_TRANSLUCENT)
                || packFragment.contains(ContractPatches.NATIVE_SHADOW_TRANSLUCENT)
                || packFragment.contains(ContractPatches.NATIVE_ADDITIVE);
        if (adapted) contract = false;
        if (contract && packFragment.contains(SundialTranslucent.MARKER)) {
            packFragment = ADAPTED.computeIfAbsent(new TextPair("fragment", source.getVertexSource().orElseThrow(),
                    packFragment), pair -> SundialTranslucent.adapt(pair.first(), pair.second()));
        }
        if (contract && packFragment.contains(SundialTranslucent.SHADOW_MARKER)) {
            packVertex = ADAPTED.computeIfAbsent(new TextPair("shadowVertex", packVertex, packFragment),
                    pair -> SundialTranslucent.shadowVertex(pair.first(), pair.second()));
        }
        GuestLodOcclusion.Source lod = key.role().shadow ? null : GuestLodOcclusion.source(pipeline);
        if (lod != null) packFragment = GuestLodOcclusion.apply(packFragment, lod,
                ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack().flywheel$nativeDepth());
        // Compat with Iris: virtual shadow names must satisfy its depth transform's shadow-name predicate.
        String transformName = key.role().shadow ? source.getName() + "_shadow" : source.getName();
        Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(transformName,
                packVertex, source.getGeometrySource().orElse(null),
                source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null),
                packFragment, alpha, false, false, true,
                !contract && key.role() == PackRole.GLINT ? GLINT_INPUTS : INPUTS,
                pipeline.getTextureMap()
                #if MC >= 26.3 , pipeline.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW) #endif);
        patched.forEach((stage, text) -> requireSupportedStage(source.getName() + "/" + stage, text));
        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        String patchedFragment = patched.get(PatchShaderType.FRAGMENT);
        if (key.farLayer()) patchedFragment = FarLayerMainGuest.fragment(patchedFragment);
        if (!contract && !adapted && alpha == AlphaTests.OFF && key.cutoutPredicate()
                && source.getDirectives().getAlphaTestOverride().isEmpty()) {
            patchedFragment = CUTOUT.computeIfAbsent(new TextPair("cutout",
                    FlwPrograms.SOURCES.get(Objects.requireNonNull(key.cutout()).source()).source(),
                    patchedFragment), pair -> cutout(pair.first(), pair.second()));
        }
        if (glyph) {
            patchedVertex = GuestGlyphAlbedo.patch(patchedVertex);
            patchedFragment = GuestGlyphAlbedo.patch(patchedFragment);
        }
        Map<String, String> albedo = Map.of();
        if (key.bindless()) {
            Bindless converted = BINDLESS.computeIfAbsent(new TextPair("bindless", patchedVertex, patchedFragment),
                    pair -> bindless(pair.first(), pair.second()));
            patchedVertex = converted.vertex();
            patchedFragment = converted.fragment();
            albedo = converted.assignments();
        }
        boolean particleHandoff = key.role() == PackRole.PARTICLES_FLATTEN || key.role() == PackRole.PARTICLES_HANDOFF;
        if (key.role() == PackRole.PARTICLES_FLATTEN) patchedFragment = GuestParticleFlatten.flatten(patchedFragment);
        if (key.role() == PackRole.PARTICLES_HANDOFF) {
            patchedFragment = GuestParticleFlatten.handoff(patchedFragment, key.alphaTest(), key.cutoutPredicate()
                            ? FlwPrograms.SOURCES.get(Objects.requireNonNull(key.cutout()).source()).source() : null,
                    !IrisRuntime.EXPERIMENTAL && GuestFlattenTarget.DEMOTE);
        }
        if (particleHandoff) {
            albedo = new LinkedHashMap<>(albedo);
            albedo.put(GuestParticleFlatten.HANDOFF_COLOR, "_flw_guestColor()");
            albedo.put(GuestParticleFlatten.UV, "flw_vertexTexCoord");
            albedo.put(GuestParticleFlatten.ID, "float(gl_InstanceID)");
        }

        boolean shadow = key.role().shadow;
        Rewritten rewritten = REWRITTEN.computeIfAbsent(
                shiftBufferBindings(modelChunkFade(patchedVertex, shadow)), text -> {
                    String transformed = TRANSFORMER.transform(text);
                    VertexInterface rewrittenInputs = Objects.requireNonNull(lastInterface);
                    lastInterface = null;
                    return new Rewritten(transformed, rewrittenInputs);
                });
        String vertex = rewritten.source();
        VertexInterface inputs = rewritten.inputs();
        // No previous shadow view. FIXME: experimental Iris (Vulkan hosts admit no loose uniforms) keeps at_velocity
        // zero: nothing supplies it there yet. Its history would carry the previous view in a push constant.
        boolean velocity = inputs.types().containsKey(VELOCITY) && !shadow && !IrisRuntime.EXPERIMENTAL;
        boolean history = velocity && key.velocity();

        boolean frozen = staticGeometry || key.farLayer();
        List<SourceComponent> body = new ArrayList<>();
        if (!frozen && key.indirect() && !key.crumbling()) {
            List<InstanceType<?>> types = InstanceTypeIds.snapshot().types();
            body.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
            body.add(FlwPrograms.SOURCES.get(MATRICES));
            body.add(new UberInstanceComponent(types, FlwPrograms.SOURCES));
            if (history) {
                body.add(UberInstanceComponent.history(types, BufferBindings.HISTORY_INSTANCE, STATIC_GEOMETRY));
            }
        } else if (!frozen) {
            InstanceType<?> type = Objects.requireNonNull(key.type());
            body.add(new InstanceStructComponent(type));
            body.add(InstanceStructComponent.typeShader(type, FlwPrograms.SOURCES.get(type.vertexShader())));
            body.add(key.indirect() || IrisRuntime.EXPERIMENTAL
                    ? new SsboInstanceComponent(type) : new BufferTextureInstanceComponent(type));
            if (history) {
                body.add(key.indirect() ? SsboInstanceComponent.history(type, BufferBindings.HISTORY_INSTANCE)
                        : BufferTextureInstanceComponent.history(type));
            }
        }
        if (!frozen) {
            body.add(FlwPrograms.SOURCES.get(key.materialVertex()));
            body.add(FlwPrograms.SOURCES.get(key.indirect() ? INDIRECT : INSTANCING));
        }

        boolean proxy = contract || PROXY_INPUTS.stream()
                                                .anyMatch(inputs.types()::containsKey);
        boolean emissive = key.role() == PackRole.ADDITIVE;
        String library = library(body, key,
                !frozen && !contract && !key.crumbling() && !emissive && key.role() != PackRole.GLINT
                        ? source.getParent().getPackDirectives() : null, contract, oit != null, proxy, emissive,
                emissive && ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack().flywheel$deferredEmissive(),
                visibleInstances, velocity, history, frozen);
        String fragment = modelChunkFade(patchedFragment, shadow);
        if (key.sortedBlendPass() == SortedBlendPass.FAST) {
            fragment = SortedBillboardBlend.applyFastFragment(fragment);
        }
        fragment = shiftBufferBindings(fragment);
        if (key.sortedBlendPass() != SortedBlendPass.FAST
                && contract && key.type() == InstanceTypes.BILLBOARD && !shadow && key.oit() == null
                && source.getParent().flywheel$deferredOit() == DeferredOitProfile.ITERATION
                && ContractProgram.byName(source.getName()) == ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT
                && OrderedBillboardCapture.enabled()) {
            fragment = OrderedBillboardCapture.apply(fragment);
        }
        if (contract) {
            String fragmentLibrary = contractLibrary(key, source.getParent().getPackDirectives(), oit != null,
                    frozen);
            if (oit != null) {
                OitFragment demoted = demoteForOit(fragment);
                fragment = demoted.source();
                fragmentLibrary += GuestOitCodegen.producer(oit, demoted.outputs());
            }
            fragment = contractFragment(fragment, fragmentLibrary);
        } else if (oit != null) {
            OitFragment demoted = demoteForOit(fragment);
            fragment = withLibrary(demoted.source(), libraryPreamble() + "in float _flw_oitViewZ;\n"
                    + FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY).source() + '\n'
                    + GuestOitCodegen.producer(oit, demoted.outputs()), null);
        }
        String geometry = shiftBufferBindings(modelChunkFade(patched.get(PatchShaderType.GEOMETRY), shadow));
        if (contract && geometry != null) {
            Compilation ctx = new Compilation();
            ShaderAssembly.appendComponents(ctx, List.of(FlwPrograms.SOURCES.get(CONTRACT_GEOMETRY)));
            geometry = afterLeadingDirectives(geometry, ctx.assembledSource());
        }
        String vertexStage = particleHandoff
                ? vertex(vertex, inputs, library + GuestParticleFlatten.VERTEX_OUTPUTS, albedo, key.sortedBlendPass(),
                false)
                  .replace("iris_Color = vec4(_flw_guestColor());", "iris_Color = vec4(1.0);")
                : vertex(vertex, inputs, library, albedo, key.sortedBlendPass(), key.farLayer());
        return new Stages(vertexStage, geometry,
                shiftBufferBindings(modelChunkFade(patched.get(PatchShaderType.TESS_CONTROL), shadow)),
                shiftBufferBindings(modelChunkFade(patched.get(PatchShaderType.TESS_EVAL), shadow)), fragment);
    }

    // Where Iris appends its alpha test (CommonTransformer): iris_FragData0 only, so packs declaring their own outputs
    // keep neither.
    private static String cutout(String predicate, String fragment) {
        var parser = versionedTransformer();
        parser.setTransformation((tree, root) -> {
            if (!root.identifierIndex.has("iris_FragData0")) return;
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, predicate);
            if (root.identifierIndex.has("flw_sampleColor")) {
                var samples = root.nodeIndex.getStream(FunctionCallExpression.class).filter(call ->
                        call.getFunctionName() != null && ALBEDO_SAMPLES.contains(call.getFunctionName().getName())
                                && !call.getParameters().isEmpty()
                                && call.getParameters().getFirst() instanceof ReferenceExpression sampler
                                && ALBEDO.contains(sampler.getIdentifier().getName())).toList();
                if (samples.isEmpty())
                    throw new UnsupportedOperationException("Native cutout requires an albedo sample");
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec4 flw_sampleColor;");
                for (var sample : samples)
                    sample.replaceByAndDelete(parser.parseExpression(root,
                            "(flw_sampleColor = " + ASTPrinter.printSimple(sample) + ")"));
            }
            tree.getOneMainDefinitionBody().getStatements()
                .add(parser.parseStatement(root, "if (flw_discardPredicate(iris_FragData0)) discard;"));
        });
        return parser.transform(fragment);
    }

    private static @Nullable String modelChunkFade(@Nullable String stage, boolean shadow) {
        if (stage == null || shadow) return stage;
        return CHUNK_FADE.computeIfAbsent(stage, GuestShaders::withoutChunkFade);
    }

    private static String withoutChunkFade(String stage) {
        var parser = versionedTransformer();
        parser.setTransformation((tree, root) -> {
            int changed = 0;
            for (ExternalDeclaration external : tree.getChildren()) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) continue;
                for (DeclarationMember member : typed.getMembers()) {
                    if (!member.getName().getName().equals("mc_chunkFade")) continue;
                    if (typed.getMembers().size() != 1
                            || !hasStorage(typed.getType().getTypeQualifier(), StorageQualifier.StorageType.CONST)
                            || !ASTPrinter.printSimple(typed.getType().getTypeSpecifier()).strip().equals("float")
                            || !(member.getInitializer() instanceof ExpressionInitializer initializer)
                            || !missingChunkFade(initializer.getExpression())) {
                        throw new IllegalStateException("Unexpected Iris model chunk-fade sentinel");
                    }
                    initializer.setExpression(parser.parseExpression(root, "1.0"));
                    changed++;
                }
            }
            if (changed != 1)
                throw new IllegalStateException("Expected one Iris model chunk-fade sentinel: " + changed);
        });
        return parser.transform(stage);
    }

    private static boolean missingChunkFade(Expression expression) {
        while (expression instanceof GroupingExpression grouping) expression = grouping.getOperand();
        if (expression instanceof NegationExpression negation) {
            expression = negation.getOperand();
            while (expression instanceof GroupingExpression grouping) expression = grouping.getOperand();
            return expression instanceof LiteralExpression literal && literal.isFloatingPoint()
                    && literal.getFloating() == 1.0;
        }
        return expression instanceof LiteralExpression literal && literal.isFloatingPoint()
                && literal.getFloating() == -1.0;
    }

    static @Nullable String shiftBufferBindings(@Nullable String stage) {
        return shiftBufferBindings(stage, GuestSsbos.BINDING_OFFSET);
    }

    /**
     * Shifts pack storage declarations above a host's borrowed geometry bindings during shader assembly.
     */
    public static @Nullable String shiftBufferBindings(@Nullable String stage, int offset) {
        if (IrisRuntime.EXPERIMENTAL || stage == null || !STORAGE_BLOCK.matcher(stage).find()) return stage;
        return SHIFTED.computeIfAbsent(offset + "\n" + stage, _ -> shifted(stage, offset));
    }

    private static String shifted(String stage, int offset) {
        var parser = versionedTransformer();
        parser.setTransformation((_, root) -> {
            for (var block : root.nodeIndex.getStream(InterfaceBlockDeclaration.class).toList()) {
                if (block.getTypeQualifier().getParts().stream()
                         .noneMatch(part -> part instanceof StorageQualifier storage
                                 && storage.storageType == StorageQualifier.StorageType.BUFFER)) continue;
                boolean found = false;
                for (var part : block.getTypeQualifier().getParts()) {
                    if (!(part instanceof LayoutQualifier layout)) continue;
                    for (var item : layout.getParts()) {
                        if (item instanceof NamedLayoutQualifierPart named && named.getName().getName()
                                                                                   .equals("binding")) {
                            String shifted = named.getExpression() instanceof LiteralExpression literal
                                    ? Long.toString(Math.addExact(literal.getInteger(), offset))
                                    : "(" + ASTPrinter.printSimple(named.getExpression()) + ") + " + offset;
                            named.setExpression(parser.parseExpression(root, shifted));
                            found = true;
                        }
                    }
                }
                if (!found) {
                    var dummy = (DeclarationExternalDeclaration) parser.parseExternalDeclaration(root,
                            "layout(binding = " + offset + ") buffer _FlwImplicitBinding { uint _flw_unused; };");
                    var implicit = (InterfaceBlockDeclaration) dummy.getDeclaration();
                    var bindingLayout = (LayoutQualifier) implicit.getTypeQualifier().getParts().getFirst();
                    var existing = block.getTypeQualifier().getParts().stream()
                                        .filter(LayoutQualifier.class::isInstance)
                                        .map(LayoutQualifier.class::cast).findFirst();
                    if (existing.isPresent())
                        existing.get().getParts().add(bindingLayout.getParts().getFirst().cloneInto(root));
                    else block.getTypeQualifier().getParts().addFirst(bindingLayout.cloneInto(root));
                    dummy.detachAndDelete();
                }
            }
        });
        return parser.transform(stage);
    }

    /**
     * {@code velocity}: compute {@code _flw_guestVelocity}; {@code history}: from the previous frame's instances and
     * embedding poses, else from this frame's (camera motion only).
     */
    private static String library(List<SourceComponent> body, ProgramKey key,
                                  @Nullable PackDirectives vertexLight, boolean contract, boolean oit, boolean proxy,
                                  boolean emissive, boolean emissivePeak, boolean visibleInstances, boolean velocity,
                                  boolean history, boolean frozen) {
        boolean crumbling = key.crumbling();
        boolean indirect = key.indirect();
        Compilation ctx = new Compilation();
        if (frozen) {
            ctx.define("_FLW_GUEST_STATIC");
            ctx.define("_FLW_GUEST_STATIC_RENDER_TYPE", Integer.toString(ExtendedDataHelper.BLOCK_RENDER_TYPE));
        }
        if (IrisRuntime.EXPERIMENTAL && !indirect) {
            ctx.define("_FLW_GUEST_STORAGE_INSTANCES");
            ctx.define("_FLW_GUEST_INSTANCE_UINTS", Integer.toString(
                    (Objects.requireNonNull(key.type()).layout().byteSize() + 15) / 16 * 4));
        }
        if (key.embedded()) {
            ctx.define("FLW_EMBEDDED");
        }
        if (!VkContext.isVulkanHost()) ctx.polyfillFmaIfMissing();
        if (key.selector()) {
            ctx.define("_FLW_INSTANCING_SELECTOR");
        } else if (crumbling || indirect) {
            ctx.requireExtension("GL_ARB_shader_draw_parameters");
        }
        if (crumbling) {
            ctx.define("_FLW_CRUMBLING");
        }
        if (indirect) {
            ctx.define("_FLW_GUEST_INDIRECT");
        }
        if (visibleInstances) {
            ctx.define("_FLW_GUEST_VISIBLE_INSTANCES");
        }
        if (velocity) {
            ctx.define("_FLW_GUEST_VELOCITY");
        }
        if (history) {
            ctx.define("_FLW_GUEST_PREV_INSTANCES");
            ctx.define("_FLW_PREV_MATRICES_BINDING", Integer.toString(BufferBindings.HISTORY_MATRICES));
            ctx.define("_FLW_PREV_MOVED_BINDING", Integer.toString(BufferBindings.HISTORY_MOVED));
            // The indirect uber shader asks _flw_timeAnimatedUber per type instead.
            if ((crumbling || !indirect) && Objects.requireNonNull(key.type()).timeAnimated()) {
                ctx.define("_FLW_GUEST_TIME_ANIMATED");
            }
        }
        if (contract) {
            ctx.define("_FLW_GUEST_CONTRACT");
        }
        if (oit) {
            ctx.define("_FLW_GUEST_OIT");
        }
        if (proxy) {
            ctx.define("_FLW_GUEST_PROXY_VERTEX");
        }
        if (emissive || key.role() == PackRole.ENTITIES) {
            ctx.define("_FLW_GUEST_MESH_LIGHT");
        }
        if (emissivePeak) {
            ctx.define("_FLW_GUEST_EMISSIVE_PEAK");
        }
        if (key.role() == PackRole.GLINT && !contract) {
            ctx.define("_FLW_GUEST_GLINT");
        }
        if (key.bindless()) {
            ctx.define("_FLW_GUEST_BINDLESS");
            ctx.define("_FLW_TEXTURE_HANDLES_BINDING", Integer.toString(GlBindlessTable.HANDLES_BINDING));
            ctx.define("_FLW_GUEST_TEXTURE_INFO_BINDING", Integer.toString(GuestTextureInfo.BINDING));
        }
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(HEADER));
        if (vertexLight != null) {
            ctx.define("_FLW_GUEST_VERTEX_LIGHT");
            if (vertexLight.shouldUseSeparateAo()) {
                ctx.define("_FLW_GUEST_SEPARATE_AO");
            }
            ctx.define("_FLW_AO_STRENGTH", Float.toString(vertexLight.getAmbientOcclusionLevel()));
            ctx.define("_FLW_AO_OPTION_ENABLED", "(_flw_renderOrigin.w != 0)");
            Objects.requireNonNull(key.smoothness())
                   .appendDefines(ctx);
            ctx.define("flw_renderOrigin", "_flw_renderOrigin.xyz");
            if (indirect || IrisRuntime.EXPERIMENTAL) {
                ctx.define("_FLW_LIGHT_LUT_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_LUT));
                ctx.define("_FLW_LIGHT_SECTIONS_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_SECTION));
            }
            roots.add(FlwPrograms.SOURCES.get(VERTEX_LIGHT_HEADER));
            roots.add(
                    FlwPrograms.SOURCES.get(indirect || IrisRuntime.EXPERIMENTAL ? INDIRECT_LIGHT : INSTANCING_LIGHT));
            roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.light())));
            roots.add(FlwPrograms.SOURCES.get(VERTEX_LIGHT));
        }
        roots.addAll(body);
        if (key.sortedBlendPass() != SortedBlendPass.NORMAL) {
            ctx.define("_FLW_SORTED_BLEND_BINDING", Integer.toString(GuestSsbos.BINDING_OFFSET + 8));
            ctx.define("_FLW_SORTED_BLEND_FAST", Boolean.toString(key.sortedBlendPass() == SortedBlendPass.FAST));
            roots.add(FlwPrograms.SOURCES.get(SORTED_BLEND_VERTEX));
        }
        ShaderAssembly.appendComponents(ctx, roots);
        if (key.farLayer()) FarLayerMainGuest.vertex(ctx);
        else if (frozen) FarStaticShadows.vertex(ctx);
        return ctx.assembledSource();
    }

    private static String contractLibrary(ProgramKey key, PackDirectives directives, boolean oit,
                                          boolean frozen) {
        Compilation ctx = new Compilation();
        if (!VkContext.isVulkanHost()) ctx.polyfillFmaIfMissing();
        if (key.role() == PackRole.TERRAIN_TRANSLUCENT) ctx.define("_FLW_GUEST_BORROWED_BLOCK");
        if (key.role() == PackRole.ADDITIVE && key.transparency() == Transparency.ADDITIVE) {
            ctx.define("_FLW_GUEST_ADDITIVE_ONE");
        }
        if (FogShaders.NONE.source().equals(key.fog())) ctx.define("_FLW_GUEST_FOG_NONE");
        ctx.define("_FLW_AO_STRENGTH", Float.toString(directives.getAmbientOcclusionLevel()));
        ctx.define("_FLW_AO_OPTION_ENABLED", "(_flw_renderOrigin.w != 0)");
        ctx.define("_FLW_CARDINAL_ENABLED", Boolean.toString(directives.isOldLighting()));
        Objects.requireNonNull(key.smoothness())
               .appendDefines(ctx);
        ctx.define("flw_renderOrigin", "_flw_renderOrigin.xyz");
        ctx.define("flw_constantAmbientLight", "_flw_constantAmbientLight");
        ctx.define("flw_light0Direction", "Light0_Direction");
        ctx.define("flw_light1Direction", "Light1_Direction");
        ctx.define("diffuse", "_flw_diffuse");
        ctx.define("diffuseNether", "_flw_diffuseNether");
        ctx.define("diffuseFromLightDirections", "_flw_diffuseFromLightDirections");
        if (key.indirect() || IrisRuntime.EXPERIMENTAL) {
            ctx.define("_FLW_LIGHT_LUT_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_LUT));
            ctx.define("_FLW_LIGHT_SECTIONS_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_SECTION));
        }
        if (key.embedded()) {
            ctx.define("FLW_EMBEDDED");
        }
        if (directives.isOldLighting()) {
            ctx.define("_FLW_GUEST_OLD_LIGHTING");
        }
        if (oit) {
            ctx.define("_FLW_GUEST_OIT");
            if (!key.role().shadow) ctx.define("_FLW_GUEST_MAIN_OIT");
        }
        CutoutShader cutout = Objects.requireNonNull(key.cutout());

        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(CONTRACT_HEADER));
        if (oit) {
            roots.add(FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY));
        }
        roots.add(FlwPrograms.SOURCES.get(DIFFUSE));
        if (frozen) {
            // Frozen casters and retained layers carry captured light; their chunks no longer belong to the live
            // light volume.
            roots.add(FlwPrograms.SOURCES.get(LightShaders.NONE.source()));
        } else {
            roots.add(FlwPrograms.SOURCES.get(
                    key.indirect() || IrisRuntime.EXPERIMENTAL ? INDIRECT_LIGHT : INSTANCING_LIGHT));
            roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.light())));
        }
        roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.materialFragment())));
        if (cutout != CutoutShaders.OFF) {
            ctx.define("_FLW_USE_DISCARD");
            roots.add(FlwPrograms.SOURCES.get(cutout.source()));
        }
        roots.add(FlwPrograms.SOURCES.get(CONTRACT));
        ShaderAssembly.appendComponents(ctx, roots);
        return ctx.assembledSource();
    }

    private static String contractFragment(String irisFragment, String library) {
        return withLibrary(irisFragment, library, CONTRACT_PROTOTYPE);
    }

    /**
     * Pack fragment before library; {@code prelude} provides forward prototypes.
     */
    static String withLibrary(String irisFragment, String library, @Nullable String prelude) {
        Matcher version = VERSION_LINE.matcher(irisFragment);
        if (!version.find()) {
            throw new IllegalStateException("Iris fragment stage without #version");
        }
        StringBuilder extensions = new StringBuilder();
        StringBuilder libraryBody = new StringBuilder();
        splitExtensions(library, extensions, libraryBody);

        String body = irisFragment.substring(version.end());
        if (prelude != null) {
            body = afterLeadingDirectives(body, prelude);
        }
        // TODO: merge transformed pack stages and engine components through Compilation.
        return versionPreamble() + extensions + body + '\n' + libraryBody;
    }

    public static OitFragment demoteForOit(String patchedFragment) {
        return DEMOTED.computeIfAbsent(patchedFragment, text -> {
            String transformed = FRAGMENT_TRANSFORMER.transform(text);
            Map<Integer, String> outputs = Objects.requireNonNull(lastOutputs);
            lastOutputs = null;
            return new OitFragment(transformed, outputs);
        });
    }

    static void clearTransformMemos() {
        BINDLESS.clear();
        CUTOUT.clear();
        ADAPTED.clear();
        CHUNK_FADE.clear();
        SHIFTED.clear();
        REWRITTEN.clear();
        DEMOTED.clear();
        GuestLodOcclusion.clearMemo();
    }

    private static String afterLeadingDirectives(String stage, String prelude) {
        Matcher version = VERSION_LINE.matcher(stage);
        int body = version.lookingAt() ? version.end() : 0;
        Matcher directive = LEADING_DIRECTIVE.matcher(stage);
        while (directive.find(body) && directive.start() == body) {
            body = directive.end();
        }
        return stage.substring(0, body) + prelude + stage.substring(body);
    }

    /**
     * Adds variant directives through Compilation before the shader patch is printed.
     */
    static void preamble(SingleASTTransformer<JobParameters> parser, TranslationUnit tree,
                         Consumer<Compilation> extra) {
        var compilation = new Compilation();
        extra.accept(compilation);
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                compilation.assembledSource().lines().filter(line -> !line.isBlank()).map(line -> line + '\n'));
    }

    /**
     * Albedo samplers and Iris's albedo-derived uniforms become flat varyings the vertex stage reads from the bindless
     * tables (GL_ARB_bindless_texture admits sampler varyings): merged batches mix textures within one draw.
     */
    private static Bindless bindless(String vertex, String fragment) {
        Map<String, String> fragmentTypes = new LinkedHashMap<>();
        String convertedFragment = albedoVaryings(fragment, "flat in", fragmentTypes, Map.of());
        Map<String, String> vertexTypes = new LinkedHashMap<>(fragmentTypes);
        String convertedVertex = albedoVaryings(vertex, "flat out", vertexTypes, fragmentTypes);
        Map<String, String> assignments = new LinkedHashMap<>();
        vertexTypes.forEach((name, type) -> assignments.put(name, ALBEDO.contains(name) ? ALBEDO_SOURCE
                : type + "(" + ALBEDO_UNIFORMS.get(name) + ")"));
        return new Bindless(convertedVertex, convertedFragment, assignments);
    }

    /**
     * {@code types}: converted name -> GLSL type; {@code required} names are declared when the stage lacks them.
     */
    private static String albedoVaryings(String stage, String storage, Map<String, String> types,
                                         Map<String, String> required) {
        var parser = versionedTransformer();
        parser.setTransformation((tree, root) -> {
            preamble(parser, tree, compilation -> compilation.requireExtension("GL_ARB_bindless_texture"));
            Set<String> declared = new HashSet<>();
            for (ExternalDeclaration external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)
                        || !hasStorage(typed.getType().getTypeQualifier(), StorageQualifier.StorageType.UNIFORM)) {
                    continue;
                }
                String type = ASTPrinter.printSimple(typed.getType().getTypeSpecifier()).strip();
                for (DeclarationMember member : List.copyOf(typed.getMembers())) {
                    String name = member.getName().getName();
                    if (member.getArraySpecifier() != null || !(ALBEDO.contains(name) ? type.equals("sampler2D")
                            : ALBEDO_UNIFORMS.containsKey(name))) {
                        continue;
                    }
                    types.put(name, type);
                    declared.add(name);
                    ExternalDeclaration varying = parser.parseExternalDeclaration(root,
                            storage + " " + type + " " + name + ";");
                    if (typed.getMembers().size() == 1) {
                        external.replaceByAndDelete(varying);
                    } else {
                        member.detachAndDelete();
                        tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, varying);
                    }
                }
            }
            required.forEach((name, type) -> {
                if (!declared.contains(name)) {
                    tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS,
                            parser.parseExternalDeclaration(root, storage + " " + type + " " + name + ";"));
                }
            });
        });
        return parser.transform(stage);
    }

    private static void splitExtensions(String library, StringBuilder extensions, StringBuilder body) {
        for (String line : library.split("\n", -1)) {
            (line.startsWith("#extension") ? extensions : body).append(line)
                                                               .append('\n');
        }
    }

    private static String vertex(String patched, VertexInterface inputs, String library, Map<String, String> albedo,
                                 SortedBlendPass sortedBlendPass, boolean farLayer) {
        Matcher version = VERSION_LINE.matcher(patched);
        if (!version.find()) {
            throw new IllegalStateException("Printed vertex stage without #version");
        }

        StringBuilder extensions = new StringBuilder();
        StringBuilder libraryBody = new StringBuilder();
        splitExtensions(library, extensions, libraryBody);

        StringBuilder out = new StringBuilder(versionPreamble());
        out.append(extensions)
           .append(patched, version.end(), patched.length())
           .append('\n')
           .append(libraryBody)
           .append("void main() {\n");
        if (sortedBlendPass != SortedBlendPass.NORMAL) {
            out.append("    if (!_flw_sortedBlendDrawEnabled()) { gl_Position = vec4(2.0, 0.0, 0.0, 1.0); return; }\n");
        }
        out.append("    _flw_guestVertex();\n");
        albedo.forEach((name, source) -> out.append("    ").append(name).append(" = ").append(source).append(";\n"));
        inputs.types()
              .forEach((name, glslType) -> out.append("    ")
                                              .append(name)
                                              .append(" = ")
                                              .append(glslType)
                                              .append('(')
                                              .append(INPUT_SOURCES.get(name))
                                              .append(");\n"));
        inputs.deferredInitializers()
              .forEach(statement -> out.append("    ")
                                       .append(statement)
                                       .append('\n'));
        out.append("    _flw_irisMain();\n");
        if (farLayer) out.append("    _flw_farLayerRaster();\n");
        return out.append("}\n")
                  .toString();
    }

    private static VertexInterface rewriteInputs(TranslationUnit tree, Root root) {
        Map<String, String> types = new LinkedHashMap<>();
        for (ExternalDeclaration external : tree.getChildren()) {
            if (!(external instanceof DeclarationExternalDeclaration declaration)
                    || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)
                    || typed.getMembers()
                            .size() != 1) {
                continue;
            }
            String name = typed.getMembers()
                               .getFirst()
                               .getName()
                               .getName();
            if (INPUT_SOURCES.containsKey(name) && hasStorage(typed.getType()
                                                                   .getTypeQualifier(),
                    StorageQualifier.StorageType.IN)) {
                types.put(name, ASTPrinter.printSimple(typed.getType()
                                                            .getTypeSpecifier())
                                          .strip());
                typed.getType()
                     .setTypeQualifier(null);
            }
        }

        List<String> deferred = new ArrayList<>();
        Pattern reference = Pattern.compile("\\b(" + String.join("|", types.keySet()) + ")\\b");
        if (!types.isEmpty()) {
            for (ExternalDeclaration external : tree.getChildren()) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) {
                    continue;
                }
                TypeQualifier qualifier = typed.getType()
                                               .getTypeQualifier();
                if (hasStorage(qualifier, StorageQualifier.StorageType.CONST)
                        || hasStorage(qualifier, StorageQualifier.StorageType.UNIFORM)) {
                    continue;
                }
                for (DeclarationMember member : typed.getMembers()) {
                    if (!(member.getInitializer() instanceof ExpressionInitializer initializer)) {
                        continue;
                    }
                    String expression = ASTPrinter.printSimple(initializer.getExpression())
                                                  .strip();
                    if (reference.matcher(expression)
                                 .find()) {
                        deferred.add(member.getName()
                                           .getName() + " = " + expression + ";");
                        member.setInitializer(null);
                    }
                }
            }
        }

        root.rename("main", "_flw_irisMain");
        return new VertexInterface(types, deferred);
    }

    static Map<Integer, String> demoteOutputs(TranslationUnit tree, Root root) {
        Map<Integer, String> outputs = new HashMap<>();
        outputs(tree).forEach((location, typed) -> {
            outputs.put(location, typed.getMembers()
                                       .getFirst()
                                       .getName()
                                       .getName());
            typed.getType()
                 .setTypeQualifier(null);
        });
        root.rename("main", "_flw_irisMain");
        return outputs;
    }

    /**
     * Fragment outputs by layout location.
     */
    static Map<Integer, TypeAndInitDeclaration> outputs(TranslationUnit tree) {
        Map<Integer, TypeAndInitDeclaration> outputs = new HashMap<>();
        for (ExternalDeclaration external : tree.getChildren()) {
            if (!(external instanceof DeclarationExternalDeclaration declaration)
                    || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)
                    || !hasStorage(typed.getType()
                                        .getTypeQualifier(), StorageQualifier.StorageType.OUT)) {
                continue;
            }
            if (typed.getMembers()
                     .size() != 1) {
                throw new IllegalStateException("Fragment output declaration with several members");
            }
            String name = typed.getMembers()
                               .getFirst()
                               .getName()
                               .getName();
            if (outputs.put(location(typed.getType()
                                          .getTypeQualifier(), name), typed) != null) {
                throw new IllegalStateException("Fragment outputs share a location: " + name);
            }
        }
        return outputs;
    }

    private static int location(TypeQualifier qualifier, String output) {
        for (TypeQualifierPart part : qualifier.getParts()) {
            if (part instanceof LayoutQualifier layout) {
                for (LayoutQualifierPart layoutPart : layout.getParts()) {
                    if (layoutPart instanceof NamedLayoutQualifierPart named && named.getName()
                                                                                     .getName()
                                                                                     .equals("location")
                            && named.getExpression() instanceof LiteralExpression literal) {
                        return (int) literal.getInteger();
                    }
                }
            }
        }
        throw new IllegalStateException("Fragment output " + output + " has no layout location");
    }

    private static boolean hasStorage(@Nullable TypeQualifier qualifier, StorageQualifier.StorageType type) {
        return qualifier != null && qualifier.getParts()
                                             .stream()
                                             .anyMatch(part -> part instanceof StorageQualifier storage
                                                     && storage.storageType == type);
    }

    record Stages(String vertex, @Nullable String geometry, @Nullable String tessControl, @Nullable String tessEval,
                  String fragment) {
    }

    record OitSpec(OitPass pass, ContractProperties.Oit oit, int[] drawBuffers, boolean shadow) {
    }

    public record OitFragment(String source, Map<Integer, String> outputs) {
    }

    private record TextPair(String kind, String first, String second) {
    }

    private record Rewritten(String source, VertexInterface inputs) {
    }

    private record Bindless(String vertex, String fragment, Map<String, String> assignments) {
    }

    private record VertexInterface(Map<String, String> types, List<String> deferredInitializers) {
    }
}
