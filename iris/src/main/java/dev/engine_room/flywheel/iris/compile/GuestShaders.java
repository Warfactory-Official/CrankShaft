package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.component.BufferTextureInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.UberInstanceComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.ShaderCache;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.SundialTranslucent;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.*;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack program -> Iris {@code patchVanilla} -> vertex stage re-fed from Flywheel's instance transform.
 */
public final class GuestShaders {
    // InternalVertex.VERTEX_FORMAT then GuestVertexExtras.FORMAT element locations; index 3 (overlay) is never read.
    static final String[] ATTRIBUTES = {"_flw_aPosition", "_flw_aColor", "_flw_aTexCoord", "_flw_aOverlay",
            "_flw_aLight", "_flw_aNormal", "_flw_aIrisEntity", "_flw_aMidTexCoord", "_flw_aTangent", "_flw_aMidBlock",
            "_flw_aFaceNormal"};

    private static final Identifier HEADER = ResourceUtil.rl("iris/guest_header.vert");
    private static final Identifier VERTEX_LIGHT_HEADER = ResourceUtil.rl("iris/guest_vertex_light_header.vert");
    private static final Identifier VERTEX_LIGHT = ResourceUtil.rl("iris/guest_vertex_light.vert");
    private static final Identifier INSTANCING = ResourceUtil.rl("iris/guest_instancing.vert");
    private static final Identifier INDIRECT = ResourceUtil.rl("iris/guest_indirect.vert");
    private static final Identifier DRAW_COMMAND = ResourceUtil.rl("internal/indirect/draw_command.glsl");
    private static final Identifier MATRICES = ResourceUtil.rl("internal/indirect/matrices.glsl");
    private static final Identifier CONTRACT_HEADER = ResourceUtil.rl("iris/guest_contract_header.frag");
    private static final Identifier CONTRACT = ResourceUtil.rl("iris/guest_contract.frag");
    private static final Identifier CONTRACT_GEOMETRY = ResourceUtil.rl("iris/guest_contract_geom.glsl");
    private static final Identifier DIFFUSE = ResourceUtil.rl("internal/diffuse.glsl");
    private static final Identifier INSTANCING_LIGHT = ResourceUtil.rl("internal/instancing/light.glsl");
    private static final Identifier INDIRECT_LIGHT = ResourceUtil.rl("internal/indirect/light.glsl");
    private static final String CONTRACT_PROTOTYPE = "void clrwl_computeFragment(vec4 sampleColor, out vec4 fragColor, "
            + "out vec2 fragLight, out float ao, out vec4 fragOverlay);\n";
    // glsl-transformer prints a line comment ahead of the pack's directives.
    private static final Pattern LEADING_DIRECTIVE = Pattern.compile(
            "[ \\t]*(?:#(?:extension|pragma)[^\\n]*|//[^\\n]*)?\\n");
    private static final Pattern STORAGE_BLOCK = Pattern.compile("\\bbuffer\\b");

    // As vec4/ivec4 sources the declared type constructs from (GLSL narrows vectors and converts int <-> float in
    // constructors).
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
            Map.entry("at_midBlock", "_flw_irisMidBlock"));
    private static final List<String> PROXY_INPUTS = List.of("mc_midTexCoord", "at_tangent", "at_midBlock");

    private static final ShaderAttributeInputs INPUTS = new ShaderAttributeInputs(IrisVertexFormats.ENTITY, false,
            false, false, false, false);

    private static final Pattern VERSION_LINE = Pattern.compile("#version\\s+(\\d+)[^\\n]*\\n");

    private static final SingleASTTransformer<JobParameters> TRANSFORMER = versionedTransformer();
    private static final SingleASTTransformer<JobParameters> FRAGMENT_TRANSFORMER = versionedTransformer();

    // Render thread only: the transformations report through here.
    private static @Nullable VertexInterface lastInterface;
    private static @Nullable Map<Integer, String> lastOutputs;

    static {
        TRANSFORMER.setTransformation((TranslationUnit tree, Root root) -> lastInterface = rewriteInputs(tree, root));
        FRAGMENT_TRANSFORMER.setTransformation(
                (TranslationUnit tree, Root root) -> lastOutputs = demoteOutputs(tree, root));
    }

    private GuestShaders() {
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
     * {@code contract}: {@code source} is a Colorwheel program; its fragment stage calls {@code clrwl_computeFragment}.
     */
    static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                        GuestPipelines.ProgramKey key, boolean contract, @Nullable OitSpec oit) {
        String packFragment = source.getFragmentSource().orElseThrow();
        String packVertex = source.getVertexSource().orElseThrow();
        if (packFragment.contains(ContractPatches.NATIVE_TRANSLUCENT)
                || packFragment.contains(ContractPatches.NATIVE_SHADOW_TRANSLUCENT)) contract = false;
        if (contract && packFragment.contains(SundialTranslucent.MARKER)) {
            packFragment = SundialTranslucent.adapt(source.getVertexSource().orElseThrow(), packFragment);
        }
        if (contract && packFragment.contains(SundialTranslucent.SHADOW_MARKER)) {
            packVertex = SundialTranslucent.shadowVertex(packVertex);
        }
        Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(source.getName(),
                packVertex, source.getGeometrySource().orElse(null),
                source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null),
                packFragment, alpha, false, false, true, INPUTS,
                pipeline.getTextureMap());

        List<SourceComponent> body = new ArrayList<>();
        if (key.indirect() && !key.crumbling()) {
            body.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
            body.add(FlwPrograms.SOURCES.get(MATRICES));
            body.add(new UberInstanceComponent(InstanceTypeIds.snapshot()
                                                              .types(), FlwPrograms.SOURCES));
        } else {
            InstanceType<?> type = Objects.requireNonNull(key.type());
            body.add(new InstanceStructComponent(type));
            body.add(FlwPrograms.SOURCES.get(type.vertexShader()));
            body.add(key.indirect() ? new SsboInstanceComponent(type) : new BufferTextureInstanceComponent(type));
        }
        body.add(FlwPrograms.SOURCES.get(key.materialVertex()));
        body.add(FlwPrograms.SOURCES.get(key.indirect() ? INDIRECT : INSTANCING));

        String vertex = TRANSFORMER.transform(shiftBufferBindings(patched.get(PatchShaderType.VERTEX)));
        VertexInterface inputs = Objects.requireNonNull(lastInterface);
        lastInterface = null;
        boolean proxy = contract || PROXY_INPUTS.stream()
                                                .anyMatch(inputs.types()::containsKey);
        // A G-buffer keeps one surface of an additive stack, whose content dims each layer for the stack: draw the
        // hue at peak, as the pack's own beacon cores are.
        boolean emissive = key.role() == PackRole.ADDITIVE;
        // Native stages read light and AO from their inputs, as from Sodium terrain; a contract's come from
        // clrwl_computeFragment.
        String library = library(body, key, !contract && !key.crumbling() && !emissive
                        ? source.getParent().getPackDirectives() : null, contract, oit != null, proxy, emissive,
                emissive && GuestPipelines.deferredEmissive(pipeline));
        String fragment = shiftBufferBindings(patched.get(PatchShaderType.FRAGMENT));
        if (contract) {
            String fragmentLibrary = contractLibrary(key, source.getParent().getPackDirectives(), oit != null);
            if (oit != null) {
                fragment = FRAGMENT_TRANSFORMER.transform(fragment);
                Map<Integer, String> outputs = Objects.requireNonNull(lastOutputs);
                lastOutputs = null;
                fragmentLibrary += GuestOitCodegen.producer(oit, outputs);
            }
            fragment = contractFragment(fragment, fragmentLibrary);
        } else if (oit != null) {
            OitFragment demoted = demoteForOit(fragment);
            fragment = withLibrary(demoted.source(), "in float _flw_oitViewZ;\n"
                    + FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY).source() + '\n'
                    + GuestOitCodegen.producer(oit, demoted.outputs()), null);
        }
        String geometry = shiftBufferBindings(patched.get(PatchShaderType.GEOMETRY));
        if (contract && geometry != null) {
            Compilation ctx = new Compilation();
            ShaderCache.expand(List.of(FlwPrograms.SOURCES.get(CONTRACT_GEOMETRY)), ctx::appendComponent);
            geometry = afterLeadingDirectives(geometry, ctx.assembledSource());
        }
        return new Stages(vertex(vertex, inputs, library), geometry,
                shiftBufferBindings(patched.get(PatchShaderType.TESS_CONTROL)),
                shiftBufferBindings(patched.get(PatchShaderType.TESS_EVAL)), fragment);
    }

    // Pack storage buffers move above the engine's (IndirectBuffers/LightBuffers/MatrixBuffer 0..7); GuestSsbos
    // binds the pack's buffers there too.
    static @Nullable String shiftBufferBindings(@Nullable String stage) {
        return shiftBufferBindings(stage, GuestSsbos.BINDING_OFFSET);
    }

    static @Nullable String shiftBufferBindings(@Nullable String stage, int offset) {
        if (stage == null || !STORAGE_BLOCK.matcher(stage).find()) return stage;
        var parser = versionedTransformer();
        parser.setTransformation((tree, root) -> {
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
                            // Older GLSL accepts integer literals here, but not arithmetic without enhanced_layouts.
                            named.setExpression(parser.parseExpression(root, shifted));
                            found = true;
                        }
                    }
                }
                if (!found) {
                    // GLSL's implicit SSBO binding is zero too; qualifiers may appear in any legal order.
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

    private static String library(List<SourceComponent> body, GuestPipelines.ProgramKey key,
                                  @Nullable PackDirectives vertexLight, boolean contract, boolean oit, boolean proxy,
                                  boolean emissive, boolean emissivePeak) {
        boolean crumbling = key.crumbling();
        boolean indirect = key.indirect();
        Compilation ctx = new Compilation();
        if (key.embedded()) {
            ctx.define("FLW_EMBEDDED");
        }
        if (crumbling || indirect) {
            ctx.requireExtension("GL_ARB_shader_draw_parameters");
        }
        if (crumbling) {
            ctx.define("_FLW_CRUMBLING");
        }
        if (indirect) {
            ctx.define("_FLW_GUEST_INDIRECT");
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
        // Emissive draws keep the mesh's light: full-bright costs a forward pack's additive draw its hue. Iris lights
        // an entities-routed layer from its lightmap.
        if (emissive || key.role() == PackRole.ENTITIES) {
            ctx.define("_FLW_GUEST_MESH_LIGHT");
        }
        if (emissivePeak) {
            ctx.define("_FLW_GUEST_EMISSIVE_PEAK");
        }
        if (key.role() == PackRole.GLINT && !contract) {
            ctx.define("_FLW_GUEST_GLINT");
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
            if (indirect) {
                ctx.define("_FLW_LIGHT_LUT_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_LUT));
                ctx.define("_FLW_LIGHT_SECTIONS_BUFFER_BINDING", String.valueOf(BufferBindings.LIGHT_SECTION));
            }
            roots.add(FlwPrograms.SOURCES.get(VERTEX_LIGHT_HEADER));
            roots.add(FlwPrograms.SOURCES.get(indirect ? INDIRECT_LIGHT : INSTANCING_LIGHT));
            roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.light())));
            roots.add(FlwPrograms.SOURCES.get(VERTEX_LIGHT));
        }
        roots.addAll(body);
        ShaderCache.expand(roots, ctx::appendComponent);
        return ctx.assembledSource();
    }

    // Emitted after the pack stage: the defines cannot reach pack code, and the unprefixed diffuse helpers are
    // renamed out of the pack's global namespace.
    private static String contractLibrary(GuestPipelines.ProgramKey key, PackDirectives directives, boolean oit) {
        Compilation ctx = new Compilation();
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
        if (key.indirect()) {
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
        }
        CutoutShader cutout = Objects.requireNonNull(key.cutout());

        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(CONTRACT_HEADER));
        if (oit) {
            roots.add(FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY));
        }
        roots.add(FlwPrograms.SOURCES.get(DIFFUSE));
        roots.add(FlwPrograms.SOURCES.get(key.indirect() ? INDIRECT_LIGHT : INSTANCING_LIGHT));
        roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.light())));
        roots.add(FlwPrograms.SOURCES.get(Objects.requireNonNull(key.materialFragment())));
        if (cutout != CutoutShaders.OFF) {
            ctx.define("_FLW_USE_DISCARD");
            roots.add(FlwPrograms.SOURCES.get(cutout.source()));
        }
        roots.add(FlwPrograms.SOURCES.get(CONTRACT));
        ShaderCache.expand(roots, ctx::appendComponent);
        return ctx.assembledSource();
    }

    private static String contractFragment(String irisFragment, String library) {
        // The pack calls the contract before the library defines it; the prototype must follow every #extension.
        return withLibrary(irisFragment, library, CONTRACT_PROTOTYPE);
    }

    /**
     * Pack fragment first, library after: the library defines what the pack already called, so anything called
     * ahead of it needs a {@code prelude} prototype.
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
        return "#version 460 core\n" + extensions + body + '\n' + libraryBody;
    }

    /**
     * The pack's fragment with {@code main} renamed and its outputs demoted to globals, keyed by output location --
     * what {@link GuestOitCodegen#producer} accumulates from.
     */
    static OitFragment demoteForOit(String patchedFragment) {
        String transformed = FRAGMENT_TRANSFORMER.transform(patchedFragment);
        Map<Integer, String> outputs = Objects.requireNonNull(lastOutputs);
        lastOutputs = null;
        return new OitFragment(transformed, outputs);
    }

    // Prelude after the #version line and every leading #extension/#pragma.
    private static String afterLeadingDirectives(String stage, String prelude) {
        Matcher version = VERSION_LINE.matcher(stage);
        int body = version.lookingAt() ? version.end() : 0;
        Matcher directive = LEADING_DIRECTIVE.matcher(stage);
        while (directive.find(body) && directive.start() == body) {
            body = directive.end();
        }
        return stage.substring(0, body) + prelude + stage.substring(body);
    }

    private static void splitExtensions(String library, StringBuilder extensions, StringBuilder body) {
        for (String line : library.split("\n", -1)) {
            (line.startsWith("#extension") ? extensions : body).append(line)
                                                               .append('\n');
        }
    }

    private static String vertex(String patched, VertexInterface inputs, String library) {
        Matcher version = VERSION_LINE.matcher(patched);
        if (!version.find()) {
            throw new IllegalStateException("Printed vertex stage without #version");
        }

        StringBuilder extensions = new StringBuilder();
        StringBuilder libraryBody = new StringBuilder();
        splitExtensions(library, extensions, libraryBody);

        StringBuilder out = new StringBuilder("#version 460 core\n");
        out.append(extensions)
           .append(patched, version.end(), patched.length())
           .append('\n')
           .append(libraryBody)
           .append("void main() {\n    _flw_guestVertex();\n");
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
        return out.append("    _flw_irisMain();\n}\n")
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

        // A global initialised from a former input (Iris's iris_MidBlock) would read it before main fills it.
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

    // OIT producers write their own targets after the pack's main: its outputs become globals.
    static Map<Integer, String> demoteOutputs(TranslationUnit tree, Root root) {
        Map<Integer, String> outputs = new HashMap<>();
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
                                          .getTypeQualifier(), name), name) != null) {
                throw new IllegalStateException("Fragment outputs share a location: " + name);
            }
            typed.getType()
                 .setTypeQualifier(null);
        }
        root.rename("main", "_flw_irisMain");
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

    /**
     * OIT producer variant of a contract translucent program; {@code drawBuffers}: the program's.
     */
    record OitSpec(OitPass pass, ContractProperties.Oit oit, int[] drawBuffers, boolean shadow) {
    }

    record OitFragment(String source, Map<Integer, String> outputs) {
    }

    private record VertexInterface(Map<String, String> types, List<String> deferredInitializers) {
    }
}
