package dev.engine_room.flywheel.iris.compile;

import com.google.common.primitives.Ints;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack {@code gbuffers_terrain} -> Iris {@code patchSodium} -> the engine's MDI draw.
 * <p>
 * The patched stage is nearly self-sufficient: it declares Sodium's vertex inputs itself (bound by element name
 * from the live {@code VertexFormat}), and its {@code _draw_id} / {@code _get_draw_translation} come from
 * {@code a_LightAndData[3]} with the same section packing the engine uses. Only the two PER-REGION uniforms need
 * bridging, and they cannot stay uniforms: one MDI call spans every visible region, so they are demoted to globals
 * the prologue fills from the engine's region buffer.
 */
final class GuestTerrainShaders {
    static final String GLOBALS_BLOCK = """
            layout(std140) uniform iris_Globals {
                ivec3 CameraBlockPos;
                vec3 CameraOffset;
                vec2 ScreenSize;
                float GlintAlpha;
                float GameTime;
                int MenuBlurRadius;
                int UseRgss;
            };
            """;
    // The transformer's own chunk-fade path; the engine fades through its own visibility buffer instead.
    // u_CurrentTime is NOT demoted: Iris supplies it, and overriding it desyncs mc_chunkFade from the value
    // Iris's own terrain draw computes.
    private static final List<String> REGION_UNIFORMS = List.of("u_RegionOffset", "u_RegionID");
    private static final Pattern VERSION_LINE = Pattern.compile("#version\\s+(\\d+)[^\\n]*\\n");
    // Mojang's Globals UBO reaches a guest program under Iris's name: GuestProgram.iris$getBlockIndex maps
    // Globals -> iris_Globals, so declaring "Globals" here would never be bound. Declared only when the patched
    // stage did not already declare it, since two declarations of one block fail to link.
    private static final String IRIS_GLOBALS = "iris_Globals";
    private static final String PROLOGUE_HEAD = """
            uniform ivec3 _flw_sodiumCameraInt;
            uniform vec3 _flw_sodiumCameraFrac;
            layout(std430, binding = 10) restrict readonly buffer _flw_RegionInputBuf {
                uvec4 _flw_regionInput[];
            };
            """;

    // Only the uniforms this Iris build actually injected are assigned; u_RegionOffset is the load-bearing one.
    private static final Map<String, String> REGION_ASSIGNMENTS = Map.of("u_RegionOffset",
            "u_RegionOffset = vec3(_flw_regionOrigin * 16 - _flw_sodiumCameraInt) - _flw_sodiumCameraFrac;",
            "u_RegionID",
            "u_RegionID = _flw_region.z;");

    private static final SingleASTTransformer<JobParameters> TRANSFORMER = GuestShaders.versionedTransformer();

    // Render thread only: the transformation reports through here.
    private static @Nullable List<String> lastDemoted;

    static {
        TRANSFORMER.setTransformation(GuestTerrainShaders::demoteRegionUniforms);
    }

    private GuestTerrainShaders() {
    }

    /**
     * Same terrain body as {@link #build}, wrapped in an OIT producer: the pack's fragment keeps its own shading
     * and its outputs become globals the generated main accumulates from. No contract is involved -- the Sodium
     * patch leaves the stage self-sufficient, exactly as it does for the opaque terrain program.
     */
    static Stages buildOit(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha,
                           int[] sourceTargets, GuestShaders.OitSpec spec) {
        Map<PatchShaderType, String> patched = patch(pipeline, source, alpha, false);
        String vertex = TRANSFORMER.transform(shift(patched.get(PatchShaderType.VERTEX)));
        List<String> demoted = Objects.requireNonNull(lastDemoted);
        lastDemoted = null;

        GuestShaders.OitFragment fragment = GuestShaders.demoteForOit(shift(patched.get(PatchShaderType.FRAGMENT)));
        String library = "in float _flw_oitViewZ;\n" + FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY)
                                                                          .source() + '\n'
                + GuestOitCodegen.producer(spec, remapOutputs(source.getName(), sourceTargets,
                spec.drawBuffers(), fragment.outputs()));
        return new Stages(vertexWithPrologue(vertex, demoted, true), shift(patched.get(PatchShaderType.GEOMETRY)),
                GuestShaders.withLibrary(fragment.source(), library, null));
    }

    static Map<Integer, String> remapOutputs(String program, int[] sourceTargets, int[] sharedTargets,
                                             Map<Integer, String> outputs) {
        if (sourceTargets.length != sharedTargets.length) {
            throw incompatibleTargets(program, sourceTargets, sharedTargets);
        }
        Map<Integer, String> mapped = new HashMap<>();
        for (int slot = 0; slot < sourceTargets.length; slot++) {
            int sharedSlot = Ints.indexOf(sharedTargets, sourceTargets[slot]);
            if (sharedSlot < 0 || mapped.containsKey(sharedSlot)) {
                throw incompatibleTargets(program, sourceTargets, sharedTargets);
            }
            String output = outputs.get(slot);
            if (output == null) {
                throw new IllegalStateException("Terrain OIT program " + program + " declares colortex"
                        + sourceTargets[slot] + " at output location " + slot + " but has no fragment output there");
            }
            mapped.put(sharedSlot, output);
        }
        return mapped;
    }

    private static IllegalStateException incompatibleTargets(String program, int[] sourceTargets, int[] sharedTargets) {
        return new IllegalStateException("Terrain OIT program " + program + " writes colortex targets "
                + Arrays.toString(sourceTargets) + ", but the shared translucent contract writes "
                + Arrays.toString(sharedTargets) + "; both producers must write the same target set");
    }

    static Map<PatchShaderType, String> patch(IrisRenderingPipeline pipeline, ProgramSource source,
                                              AlphaTest alpha, boolean shadow) {
        return TransformPatcher.patchSodium(source.getName(), source.getVertexSource().orElseThrow(),
                source.getGeometrySource().orElse(null), source.getTessControlSource().orElse(null),
                source.getTessEvalSource().orElse(null), source.getFragmentSource().orElseThrow(), alpha,
                pipeline.getTextureMap(), shadow);
    }

    static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha, boolean shadow) {
        Map<PatchShaderType, String> patched = patch(pipeline, source, alpha, shadow);

        // A pack's own storage buffers would otherwise sit on the engine's bindings (0..7) and terrain's own
        // region/fade buffers (10, 11); GuestSsbos rebinds them at the shifted slots for the draw.
        String vertex = TRANSFORMER.transform(shift(patched.get(PatchShaderType.VERTEX)));
        List<String> demoted = Objects.requireNonNull(lastDemoted);
        lastDemoted = null;
        return new Stages(vertexWithPrologue(vertex, demoted, false), shift(patched.get(PatchShaderType.GEOMETRY)),
                shift(patched.get(PatchShaderType.FRAGMENT)));
    }

    private static @Nullable String shift(@Nullable String stage) {
        return GuestShaders.shiftBufferBindings(stage, GuestSsbos.TERRAIN_BINDING_OFFSET);
    }

    private static String vertexWithPrologue(String vertex, List<String> demoted, boolean oit) {
        Matcher version = VERSION_LINE.matcher(vertex);
        if (!version.find()) {
            throw new IllegalStateException("Patched terrain vertex stage without #version");
        }
        StringBuilder main = new StringBuilder("""
                void main() {
                    uvec4 _flw_region = _flw_regionInput[gl_BaseInstanceARB];
                    ivec3 _flw_regionOrigin = _flw_unpackRegionOrigin(_flw_region);
                """);
        for (String name : demoted) {
            main.append("    ")
                .append(REGION_ASSIGNMENTS.get(name))
                .append('\n');
        }
        main.append("    _flw_irisMain();\n");
        if (oit) {
            // clip.w == -viewZ for the projection Sodium builds, so the OIT library's linear depth
            // needs no inverse.
            main.append("    _flw_oitViewZ = -gl_Position.w;\n");
        }
        main.append("}\n");
        String body = vertex.substring(version.end());
        List<SourceComponent> parts = new ArrayList<>();
        parts.add(FlwPrograms.SOURCES.get(ResourceUtil.rl("internal/terrain_region_input.glsl")));
        parts.add(new ShaderAssembly.RawSource("pack terrain vertex", body));
        if (!body.contains(IRIS_GLOBALS)) parts.add(new ShaderAssembly.RawSource("terrain globals", GLOBALS_BLOCK));
        parts.add(new ShaderAssembly.RawSource("terrain region bridge", PROLOGUE_HEAD));
        if (oit) parts.add(new ShaderAssembly.RawSource("terrain view depth", "out float _flw_oitViewZ;\n"));
        parts.add(new ShaderAssembly.RawSource("terrain entry", main.toString()));
        return ShaderAssembly.assemble(ctx -> ctx.requireExtension("GL_ARB_shader_draw_parameters"), parts);
    }

    private static void demoteRegionUniforms(TranslationUnit tree, Root root) {
        List<String> demoted = new ArrayList<>();
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
            TypeQualifier qualifier = typed.getType()
                                           .getTypeQualifier();
            if (REGION_UNIFORMS.contains(name) && hasStorage(qualifier, StorageQualifier.StorageType.UNIFORM)) {
                typed.getType()
                     .setTypeQualifier(null);
                demoted.add(name);
            }
        }
        if (!demoted.contains("u_RegionOffset")) {
            throw new IllegalStateException("Sodium patch declared no u_RegionOffset uniform to bridge");
        }
        lastDemoted = demoted;
        root.rename("main", "_flw_irisMain");
    }

    private static boolean hasStorage(@Nullable TypeQualifier qualifier, StorageQualifier.StorageType type) {
        return qualifier != null && qualifier.getParts()
                                             .stream()
                                             .anyMatch(part -> part instanceof StorageQualifier storage
                                                     && storage.storageType == type);
    }

    record Stages(String vertex, @Nullable String geometry, String fragment) {
    }
}
