package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.*;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.NVMeshShader;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class GuestTerrainMeshShaders {
    private static final Pattern VERSION = Pattern.compile("#version\\s+(\\d+)");
    private static final Pattern FORMAT = Pattern.compile("(R|RG|RGB|RGBA)(8|16|32)_(UINT|SINT|UNORM|SNORM|FLOAT)");
    private static final int QUADS = 8;

    private GuestTerrainMeshShaders() {
    }

    static Stages build(IrisRenderingPipeline pipeline, ProgramSource source, AlphaTest alpha, boolean shadow,
                        int[] sourceTargets, GuestShaders.@Nullable OitSpec oit) {
        Map<PatchShaderType, String> patched = GuestTerrainShaders.patch(pipeline, source, alpha, shadow);
        if (Compilation.DUMP_SHADER_SOURCE) {
            GuestPipelines.dump("flywheel:iris/" + source.getName() + "_mesh_input", new GuestShaders.Stages(
                    patched.get(PatchShaderType.VERTEX), patched.get(PatchShaderType.GEOMETRY), null, null,
                    patched.get(PatchShaderType.FRAGMENT)));
        }
        boolean hasGeometry = patched.get(PatchShaderType.GEOMETRY) != null;
        // Lightweight vertex programs can cost less than the task handoff and temporal-recovery machinery.
        boolean direct = !shadow && oit == null && !hasGeometry && Boolean.getBoolean("crankshaft.iris.mesh.direct");
        if (patched.get(PatchShaderType.TESS_CONTROL) != null
                || patched.get(PatchShaderType.TESS_EVAL) != null) {
            throw new IllegalStateException("Mesh terrain does not support tessellation stages: " + source.getName());
        }
        Stage vertex = new Stage();
        Stage fragment = new Stage();
        SingleASTTransformer<JobParameters> transformer = transformer();
        transformer.setTransformation((tree, root) -> {
            vertex.sharedDeclarations = GuestGeometryStage.sharedDeclarations(tree);
            if (!hasGeometry) vertex.constants = GuestQuadConstants.find(tree);
            root.rename("main", "_flw_vertexMain");
            root.rename("gl_Position", "_flw_vertexPosition");
            root.rename("gl_VertexID", "_flw_vertexId");
            root.rename("gl_BaseVertexARB", "_flw_baseVertex");
            root.rename("gl_BaseVertex", "_flw_baseVertex");
            root.rename("gl_BaseInstanceARB", "_flw_baseInstance");
            root.rename("gl_BaseInstance", "_flw_baseInstance");
            root.rename("gl_InstanceID", "_flw_instanceId");
            transformInterface(transformer, tree, vertex, true);
            tree.setVersionStatement(null);
        });
        String vertexBody = transformer.transform(shift(patched.get(PatchShaderType.VERTEX)));
        GuestGeometryStage.Result geometry = hasGeometry ? GuestGeometryStage.build(
                shift(patched.get(PatchShaderType.GEOMETRY)),
                vertex.sharedDeclarations) : null;
        // Rebuilding the indirect stream can change gl_DrawID between phases; it is not a stable position input.
        boolean taskCull = !direct && !shadow && !hasGeometry && !vertex.sideEffects && !vertex.resourceReads && !vertex.drawIdSensitive
                && !Boolean.getBoolean("crankshaft.iris.mesh.taskOff");
        transformer.setTransformation((tree, root) -> {
            transformInterface(transformer, tree, fragment, false);
            if (oit != null) fragment.fragmentOutputs = GuestShaders.demoteOutputs(tree, root);
            else root.rename("main", "_flw_fragmentMain");
            tree.setVersionStatement(null);
        });
        String fragmentBody = transformer.transform(shift(patched.get(PatchShaderType.FRAGMENT)));
        boolean depthSafe = !fragment.sideEffects && !fragment.depthWrites && !fragment.resourceReads
                && !Boolean.getBoolean("crankshaft.iris.mesh.hizOff");
        int quads = geometry == null ? QUADS : geometryQuads(geometry, fragment.inputs.size() + (oit == null ? 0 : 1));
        int taskQuads = direct ? quads : taskCull ? QUADS : 64;

        Map<String, Varying> vertexOutputs = new HashMap<>();
        Stage producerStage = geometry == null ? vertex : geometry.stage();
        for (Varying output : producerStage.outputs) vertexOutputs.put(output.key(), output);
        StringBuilder vertexMembers = new StringBuilder();
        StringBuilder primitiveMembers = new StringBuilder();
        StringBuilder stores = new StringBuilder("void _flw_storeVertex(uint index) {\n");
        StringBuilder quadStores = new StringBuilder("void _flw_storeQuad(uint quad) {\n");
        StringBuilder loads = new StringBuilder("void _flw_loadFragmentInputs() {\n");
        int location = 1;
        int vertexLocations = 0;
        int primitiveLocations = 0;
        for (Varying input : fragment.inputs) {
            Varying output = vertexOutputs.get(input.key());
            if (output == null || !output.type().equals(input.type())) {
                throw new IllegalStateException(
                        source.getName() + " has no matching vertex output for " + input.name());
            }
            int components = components(input.type());
            String vector = vectorType(input.type());
            String packed = "_flw_varying" + location;
            String qualification = input.interpolation().isEmpty() ? "" : input.interpolation() + " ";
            location++;
            boolean constant = !hasGeometry && !vertex.sideEffects && !vertex.resourceReads
                    && !Boolean.getBoolean("crankshaft.iris.mesh.constantsOff")
                    && vertex.constants.contains(output.name().split("[.\\[]", 2)[0]);
            StringBuilder members = constant ? primitiveMembers : vertexMembers;
            members.append(constant ? "" : qualification).append(vector).append(' ').append(packed).append(";\n");
            StringBuilder destination = constant ? quadStores : stores;
            destination.append(constant ? "    _flw_primitiveOut[quad * 2u]." : "    _flw_vertexOut[index].")
                       .append(packed).append(" = ").append(vector).append('(').append(output.name());
            for (int c = components; c < 4; c++) destination.append(", 0");
            destination.append(");\n");
            if (constant) {
                primitiveLocations++;
                quadStores.append("    _flw_primitiveOut[quad * 2u + 1u].").append(packed)
                          .append(" = _flw_primitiveOut[quad * 2u].").append(packed).append(";\n");
            } else vertexLocations++;
            loads.append("    ").append(input.name()).append(constant ? " = _flw_primitiveIn." : " = _flw_vertexIn.")
                 .append(packed).append('.')
                 .append("xyzw", 0, components).append(";\n");
        }
        if (oit != null) {
            vertexMembers.append("float _flw_meshViewZ;\n");
            vertexLocations++;
            stores.append("    _flw_vertexOut[index]._flw_meshViewZ = -")
                  .append(hasGeometry ? "_flw_geometryPosition" : "_flw_vertexPosition").append(".w;\n");
            loads.append("    _flw_oitViewZ = _flw_vertexIn._flw_meshViewZ;\n");
        }
        StringBuilder meshInterface = new StringBuilder();
        StringBuilder fragmentInterface = new StringBuilder();
        if (vertexLocations != 0) {
            meshInterface.append("layout(location = 1) out _FlwTerrainVertex {\n").append(vertexMembers)
                         .append("} _flw_vertexOut[];\n");
            fragmentInterface.append("layout(location = 1) in _FlwTerrainVertex {\n").append(vertexMembers)
                             .append("} _flw_vertexIn;\n");
        }
        if (primitiveLocations != 0) {
            String start = "layout(location = " + (vertexLocations + 1) + ") perprimitiveNV ";
            meshInterface.append(start).append("out _FlwTerrainPrimitive {\n").append(primitiveMembers)
                         .append("} _flw_primitiveOut[];\n");
            fragmentInterface.append(start).append("in _FlwTerrainPrimitive {\n").append(primitiveMembers)
                             .append("} _flw_primitiveIn;\n");
        }
        if (oit != null) fragmentInterface.append("float _flw_oitViewZ;\n");
        stores.append("}\n");
        quadStores.append("}\n");
        for (String initializer : fragment.initializers) loads.append("    ").append(initializer).append('\n');
        loads.append("}\n");

        boolean quadConstants = primitiveLocations != 0;
        Consumer<Compilation> defines = ctx -> {
            ctx.define("_FLW_MESH_QUADS", Integer.toString(quads));
            ctx.define("_FLW_TASK_QUADS", Integer.toString(taskQuads));
            if (geometry != null) ctx.define("_FLW_GS_MAX_VERTICES", Integer.toString(geometry.maxVertices()));
            ctx.define("_FLW_VERTEX_WORDS", Integer.toString(TerrainVertexFormat.strideBytes() / 4));
            if (taskCull) ctx.define("_FLW_TASK_CULL");
            if (quadConstants) ctx.define("_FLW_QUAD_CONSTANTS");
            if (depthSafe) ctx.define("_FLW_TASK_DEPTH_SAFE");
            if (Boolean.getBoolean("crankshaft.iris.mesh.forceRecovery")) ctx.define("_FLW_FORCE_RECOVERY");
            if (GuestTerrainGate.CACHE_RECOVERY) ctx.define("_FLW_CACHE_RECOVERY");
            if (direct) ctx.define("_FLW_MESH_DIRECT");
        };
        Consumer<Compilation> meshExtensions = defines.andThen(ctx -> {
            ctx.requireExtension("GL_NV_mesh_shader");
            ctx.requireExtension("GL_NV_gpu_shader5");
            ctx.requireExtension("GL_NV_shader_buffer_load");
            ctx.requireExtension("GL_ARB_shader_draw_parameters");
            if (taskCull) {
                ctx.requireExtension("GL_KHR_shader_subgroup_basic");
                ctx.requireExtension("GL_KHR_shader_subgroup_arithmetic");
            }
        });
        String builtins = """
                uniform ivec3 _flw_sodiumCameraInt;
                uniform vec3 _flw_sodiumCameraFrac;
                vec4 _flw_vertexPosition;
                int _flw_vertexId;
                int _flw_baseVertex;
                int _flw_baseInstance;
                int _flw_instanceId;
                uint _flw_regionSlot;
                uint _flw_sourceBaseVertex;
                restrict _FlwGuestVertex * _flw_geometry;
                """;
        List<SourceComponent> taskParts = new ArrayList<>();
        if (taskCull) {
            taskParts.add(resource("scene.glsl"));
            taskParts.add(raw("task vertex builtins", builtins));
            if (!vertex.globals) taskParts.add(raw("task globals", GuestTerrainShaders.GLOBALS_BLOCK));
            taskParts.add(raw("task position slice", vertexBody));
            taskParts.add(raw("task vertex decode", decoder(vertex)));
        }
        taskParts.add(resource(GuestTerrainGate.CACHE_RECOVERY ? "task_cached.glsl" : "task.glsl"));
        String task = direct ? null : ShaderAssembly.assemble(meshExtensions, taskParts);
        List<SourceComponent> meshParts = new ArrayList<>();
        int maxVertices = geometry == null ? quads * 4 : quads * 2 * geometry.maxVertices();
        int maxPrimitives = geometry == null ? quads * 2 : quads * 2 * (geometry.maxVertices() - 2);
        meshParts.add(
                raw("mesh layout", "layout(local_size_x = 32) in;\nlayout(triangles, max_vertices = " + maxVertices
                        + ", max_primitives = " + maxPrimitives + ") out;\n"));
        meshParts.add(resource("scene.glsl"));
        meshParts.add(raw("mesh interface", meshInterface.toString()));
        meshParts.add(raw("vertex builtins", builtins));
        if (!vertex.globals) meshParts.add(raw("globals", GuestTerrainShaders.GLOBALS_BLOCK));
        meshParts.add(raw("pack vertex", vertexBody));
        meshParts.add(raw("vertex decode", decoder(vertex)));
        if (geometry != null) {
            meshParts.add(raw("geometry builtins", """
                    struct _FlwGeometryVertex { vec4 gl_Position; };
                    _FlwGeometryVertex _flw_geometryIn[3];
                    vec4 _flw_geometryPosition;
                    int _flw_geometryPrimitiveIn;
                    int _flw_geometryPrimitiveOut;
                    int _flw_geometryInvocation;
                    int _flw_geometryLayer;
                    int _flw_geometryViewport;
                    void _flw_emitGeometry();
                    void _flw_endGeometry();
                    """));
            meshParts.add(raw("pack geometry", geometry.body()));
            meshParts.add(raw("geometry inputs", geometryBridge(vertex, geometry.stage(), quads)));
        }
        meshParts.add(raw("vertex outputs", stores.toString()));
        meshParts.add(raw("quad outputs", quadStores.toString()));
        meshParts.add(resource(geometry == null ? "mesh.glsl" : "geometry_mesh.glsl"));
        String mesh = ShaderAssembly.assemble(meshExtensions, meshParts);

        List<SourceComponent> fragmentParts = new ArrayList<>();
        fragmentParts.add(raw("fragment interface", fragmentInterface.toString()));
        fragmentParts.add(raw("pack fragment", fragmentBody));
        fragmentParts.add(raw("fragment inputs", loads.toString()));
        if (oit == null) {
            fragmentParts.add(
                    raw("fragment entry", "void main() { _flw_loadFragmentInputs(); _flw_fragmentMain(); }\n"));
        } else {
            // The producer calls the demoted pack main; its inputs are seeded at that call boundary.
            fragmentParts.add(resourceOit());
            String producer = GuestOitCodegen.producer(oit, GuestTerrainShaders.remapOutputs(source.getName(),
                    sourceTargets, oit.drawBuffers(), fragment.fragmentOutputs));
            SingleASTTransformer<JobParameters> entry = transformer();
            entry.setTransformation((tree, root) -> {
                root.rename("_flw_irisMain", "_flw_seededPackMain");
                tree.setVersionStatement(null);
            });
            fragmentParts.add(raw("seeded fragment",
                    "void _flw_seededPackMain() { _flw_loadFragmentInputs(); _flw_irisMain(); }\n"));
            fragmentParts.add(raw("OIT entry", entry.transform("#version 460 core\n" + producer)));
        }
        return new Stages(task, mesh, ShaderAssembly.assemble(defines, fragmentParts), quads, taskQuads,
                taskCull && depthSafe,
                !vertex.drawIdSensitive && !fragment.drawIdSensitive);
    }

    private static int geometryQuads(GuestGeometryStage.Result geometry, int outputs) {
        int memory = GL11C.glGetInteger(NVMeshShader.GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV);
        int vertexGranularity = GL11C.glGetInteger(NVMeshShader.GL_MESH_OUTPUT_PER_VERTEX_GRANULARITY_NV);
        int primitiveGranularity = GL11C.glGetInteger(NVMeshShader.GL_MESH_OUTPUT_PER_PRIMITIVE_GRANULARITY_NV);
        int maxVertices = GL11C.glGetInteger(NVMeshShader.GL_MAX_MESH_OUTPUT_VERTICES_NV);
        int maxPrimitives = GL11C.glGetInteger(NVMeshShader.GL_MAX_MESH_OUTPUT_PRIMITIVES_NV);
        for (int quads = QUADS; quads > 0; quads /= 2) {
            int vertices = quads * 2 * geometry.maxVertices();
            int primitives = quads * 2 * (geometry.maxVertices() - 2);
            int vertexBytes = ((vertices + vertexGranularity - 1) / vertexGranularity * vertexGranularity) * (outputs + 1) * 16;
            int primitiveBytes = ((primitives + primitiveGranularity - 1) / primitiveGranularity * primitiveGranularity) * 16;
            int sharedBytes = quads * 4 * (geometry.stage().inputs.size() + 1) * 16;
            if (vertices <= maxVertices && primitives <= maxPrimitives && vertexBytes + primitiveBytes + sharedBytes <= memory)
                return quads;
        }
        throw new IllegalStateException(
                "Geometry interface exceeds mesh output/shared-memory limits even at one quad per group");
    }

    private static ShaderAssembly.RawSource raw(String name, String source) {
        return new ShaderAssembly.RawSource(name, source);
    }

    private static SourceComponent resource(String name) {
        return FlwPrograms.SOURCES.get(Identifier.fromNamespaceAndPath("meshlet", "iris/terrain/" + name));
    }

    private static SourceComponent resourceOit() {
        return FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY);
    }

    private static String shift(String source) {
        return GuestShaders.shiftBufferBindings(source, GuestSsbos.TERRAIN_BINDING_OFFSET);
    }

    static SingleASTTransformer<JobParameters> transformer() {
        SingleASTTransformer<JobParameters> transformer = new SingleASTTransformer<>() {
            @Override
            public TranslationUnit parseTranslationUnit(Root root, String input) {
                var matcher = VERSION.matcher(input);
                if (matcher.find()) getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));
                return super.parseTranslationUnit(root, input);
            }
        };
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        transformer.setPrintType(PrintType.SIMPLE);
        return transformer;
    }

    static void transformInterface(SingleASTTransformer<JobParameters> parser, TranslationUnit tree,
                                   Stage stage, boolean vertex) {
        stage.drawIdSensitive = tree.getRoot().identifierIndex.has("gl_DrawID") || tree.getRoot().identifierIndex.has(
                "gl_DrawIDARB");
        stage.depthWrites = tree.getRoot().identifierIndex.has("gl_FragDepth");
        stage.resourceReads = tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class)
                                                      .anyMatch(
                                                              call -> call.getFunctionName() != null && (call.getFunctionName()
                                                                                                             .getName()
                                                                                                             .equals("imageLoad")
                                                                      || call.getFunctionName().getName()
                                                                             .startsWith("clock")));
        stage.sideEffects = tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class)
                                                    .anyMatch(call -> call.getFunctionName() != null && (
                                                            call.getFunctionName().getName().startsWith("atomic")
                                                                    || call.getFunctionName().getName()
                                                                           .startsWith("imageAtomic")
                                                                    || call.getFunctionName().getName()
                                                                           .equals("imageStore")));
        for (var external : List.copyOf(tree.getChildren())) {
            if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
            if (declaration.getDeclaration() instanceof InterfaceBlockDeclaration block) {
                stage.globals |= block.getBlockName().getName().equals("iris_Globals");
                // Another terrain program can write a nominally readonly stage's storage between evaluations.
                stage.resourceReads |= storage(block.getTypeQualifier(), StorageQualifier.StorageType.BUFFER);
                stage.sideEffects |= storage(block.getTypeQualifier(), StorageQualifier.StorageType.BUFFER)
                        && !storage(block.getTypeQualifier(), StorageQualifier.StorageType.READONLY);
                boolean input = storage(block.getTypeQualifier(), StorageQualifier.StorageType.IN);
                if (input || (vertex && storage(block.getTypeQualifier(), StorageQualifier.StorageType.OUT))) {
                    boolean geometryInput = stage.geometry && input;
                    if (block.getArraySpecifier() != null && !geometryInput) {
                        throw new IllegalStateException(
                                "Arrayed interface blocks are not supported: " + block.getBlockName().getName());
                    }
                    String instance = block.getVariableName() == null ? "" : block.getVariableName().getName();
                    if (geometryInput) geometryArray(parser, tree, block.getArraySpecifier());
                    String selector = instance + (geometryInput ? "[_flw_gsVertex]" : "");
                    StringBuilder body = new StringBuilder();
                    for (var member : block.getStructBody().getMembers()) {
                        String type = typeName(member.getType().getTypeSpecifier());
                        String interpolation = interpolation(member.getType().getTypeQualifier());
                        if (interpolation.isEmpty()) interpolation = interpolation(block.getTypeQualifier());
                        for (var item : member.getDeclarators()) {
                            String name = item.getName().getName();
                            varying(input ? stage.inputs : stage.outputs,
                                    instance.isEmpty() ? name : selector + '.' + name,
                                    type, block.getBlockName().getName() + '.' + name, interpolation,
                                    arrays(item.getArraySpecifier(),
                                            member.getType().getTypeSpecifier().getArraySpecifier()));
                        }
                        if (member.getType().getTypeQualifier() != null) member.getType().setTypeQualifier(null);
                        body.append(ASTPrinter.printSimple(member));
                    }
                    String replacement = instance.isEmpty() ? body.toString()
                            : "struct " + (stage.geometry ? "_flw_gs_block_" : "_flw_block_")
                            + block.getBlockName().getName() + " { " + body + " } " + instance
                            + (geometryInput ? "[3]" : "") + ";";
                    if (instance.isEmpty()) {
                        var parsed = parser.parseTranslationUnit(tree.getRoot(), replacement);
                        int position = tree.getChildren().indexOf(external);
                        for (var item : List.copyOf(parsed.getChildren())) {
                            item.detach();
                            tree.getChildren().add(position++, item);
                        }
                        external.detachAndDelete();
                    } else {
                        external.replaceByAndDelete(parser.parseExternalDeclaration(tree.getRoot(), replacement));
                    }
                }
                continue;
            }
            if (!(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) continue;
            TypeQualifier qualifiers = typed.getType().getTypeQualifier();
            boolean input = storage(qualifiers, StorageQualifier.StorageType.IN);
            boolean output = storage(qualifiers, StorageQualifier.StorageType.OUT);
            boolean uniform = storage(qualifiers, StorageQualifier.StorageType.UNIFORM);
            String type = typeName(typed.getType().getTypeSpecifier());
            for (var member : typed.getMembers()) {
                String name = member.getName().getName();
                stage.resourceReads |= uniform && type.endsWith("samplerBuffer") && !name.equals("u_SectionTimeInfo");
                if (input || (vertex && output)) {
                    if (stage.geometry && input) {
                        geometryArray(parser, tree, member.getArraySpecifier() != null ? member.getArraySpecifier()
                                : typed.getType().getTypeSpecifier().getArraySpecifier());
                    }
                    if (!input || tree.getRoot().identifierIndex.getStream(name)
                                                                .anyMatch(
                                                                        identifier -> identifier.getParent() instanceof ReferenceExpression)) {
                        ArraySpecifier array = arrays(member.getArraySpecifier(),
                                typed.getType().getTypeSpecifier().getArraySpecifier());
                        String access = name;
                        if (stage.geometry && input) {
                            array = tail(array);
                            access += "[_flw_gsVertex]";
                        }
                        String original = stage.geometry && name.startsWith(GuestGeometryStage.PREFIX)
                                ? name.substring(GuestGeometryStage.PREFIX.length()) : name;
                        varying(input ? stage.inputs : stage.outputs, access, type, key(qualifiers, original),
                                interpolation(qualifiers), array);
                    }
                }
                if (vertex && uniform && (name.equals("u_RegionOffset") || name.equals("u_RegionID"))) {
                    stage.regions.add(name);
                    typed.getType().setTypeQualifier(null);
                }
                if (!uniform && !storage(qualifiers, StorageQualifier.StorageType.CONST)
                        && member.getInitializer() != null) {
                    ArraySpecifier array = member.getArraySpecifier() != null ? member.getArraySpecifier()
                            : typed.getType().getTypeSpecifier().getArraySpecifier();
                    if (array != null && array.getDimensions().getFirst() == null) {
                        if (!(member.getInitializer() instanceof ExpressionInitializer initializer)
                                || !(initializer.getExpression() instanceof FunctionCallExpression constructor)
                                || constructor.getFunctionSpecifier() == null
                                || constructor.getFunctionSpecifier().getArraySpecifier() == null) {
                            throw new IllegalStateException("Cannot infer mesh global array size: " + name);
                        }
                        array.getDimensions().set(0, parser.parseExpression(tree.getRoot(),
                                Integer.toString(constructor.getParameters().size())));
                    }
                    stage.initializers.add(name + " = " + ASTPrinter.printSimple(member.getInitializer()) + ";");
                    member.setInitializer(null);
                }
            }
            if (input || (vertex && output)) typed.getType().setTypeQualifier(null);
        }
    }

    static boolean storage(@Nullable TypeQualifier qualifiers, StorageQualifier.StorageType kind) {
        return qualifiers != null && qualifiers.getParts().stream()
                                               .anyMatch(
                                                       part -> part instanceof StorageQualifier storage && storage.storageType == kind);
    }

    private static String key(@Nullable TypeQualifier qualifiers, String name) {
        if (qualifiers != null) for (var part : qualifiers.getParts()) {
            if (part instanceof LayoutQualifier layout) for (var item : layout.getParts()) {
                if (item instanceof NamedLayoutQualifierPart named && named.getName().getName().equals("location")
                        && named.getExpression() instanceof LiteralExpression literal)
                    return "@" + literal.getInteger();
            }
        }
        return name;
    }

    private static String interpolation(@Nullable TypeQualifier qualifiers) {
        StringBuilder result = new StringBuilder();
        if (qualifiers != null) for (var part : qualifiers.getParts()) {
            if (part instanceof InterpolationQualifier || part instanceof StorageQualifier storage
                    && (storage.storageType == StorageQualifier.StorageType.CENTROID
                    || storage.storageType == StorageQualifier.StorageType.SAMPLE)) {
                if (!result.isEmpty()) result.append(' ');
                result.append(ASTPrinter.printSimple(part).trim());
            }
        }
        return result.toString();
    }

    private static void varying(List<Varying> target, String name, String type, String key,
                                String interpolation, @Nullable ArraySpecifier array) {
        if (array != null && !array.getDimensions().isEmpty()) {
            var dimension = array.getDimensions().getFirst();
            if (!(dimension instanceof LiteralExpression literal) || literal.getInteger() <= 0 || literal.getInteger() > 256) {
                throw new IllegalStateException("Mesh varying requires a bounded literal array size: " + name);
            }
            ArraySpecifier rest = tail(array);
            for (int i = 0; i < literal.getInteger(); i++) {
                varying(target, name + '[' + i + ']', type, key + '[' + i + ']', interpolation, rest);
            }
        } else if (type.matches("mat[234](x[234])?")) {
            int columns = type.charAt(3) - '0';
            int rows = type.charAt(type.length() - 1) - '0';
            for (int column = 0; column < columns; column++) {
                target.add(
                        new Varying(name + '[' + column + ']', "vec" + rows, key + '[' + column + ']', interpolation));
            }
        } else {
            target.add(new Varying(name, type, key, interpolation));
        }
    }

    private static @Nullable ArraySpecifier tail(ArraySpecifier array) {
        return array.getDimensions().size() == 1 ? null : array.getRoot().indexNodes(() -> new ArraySpecifier(
                array.getDimensions().stream().skip(1).map(value -> value == null ? null : value.clone())));
    }

    private static void geometryArray(SingleASTTransformer<JobParameters> parser, TranslationUnit tree,
                                      @Nullable ArraySpecifier array) {
        if (array == null) throw new IllegalStateException("Geometry input must have a per-primitive array");
        var length = array.getDimensions().getFirst();
        if (length != null && (!(length instanceof LiteralExpression literal) || literal.getInteger() != 3)) {
            throw new IllegalStateException("Triangle geometry input must have three vertices");
        }
        array.getDimensions().set(0, parser.parseExpression(tree.getRoot(), "3"));
    }

    private static String geometryBridge(Stage vertex, Stage geometry, int quads) {
        Map<String, Varying> outputs = new HashMap<>();
        for (Varying output : vertex.outputs) outputs.put(output.key(), output);
        StringBuilder declarations = new StringBuilder("shared vec4 _flw_gsPositions[" + (quads * 4) + "];\n");
        StringBuilder store = new StringBuilder(
                "void _flw_storeGeometryInput(uint index) {\n    _flw_gsPositions[index] = _flw_vertexPosition;\n");
        StringBuilder load = new StringBuilder(
                "void _flw_loadGeometryInput(uvec3 vertices) {\n    for (int _flw_gsVertex = 0; _flw_gsVertex < 3; ++_flw_gsVertex) {\n"
                        + "        _flw_geometryIn[_flw_gsVertex].gl_Position = _flw_gsPositions[vertices[_flw_gsVertex]];\n");
        int serial = 0;
        for (Varying input : geometry.inputs) {
            Varying output = outputs.get(input.key());
            if (output == null || !output.type().equals(input.type()))
                throw new IllegalStateException("Missing vertex/geometry interface " + input.key());
            String shared = "_flw_gsInput" + serial++;
            declarations.append("shared ").append(input.type()).append(' ').append(shared).append('[').append(quads * 4)
                        .append("];\n");
            store.append("    ").append(shared).append("[index] = ").append(output.name()).append(";\n");
            load.append("        ").append(input.name()).append(" = ").append(shared)
                .append("[vertices[_flw_gsVertex]];\n");
        }
        store.append("}\n");
        load.append("    }\n");
        for (String initializer : geometry.initializers) load.append("    ").append(initializer).append('\n');
        load.append("}\n");
        return declarations.append(store).append(load).toString();
    }

    private static String typeName(TypeSpecifier type) {
        ArraySpecifier array = type.getArraySpecifier();
        if (array == null) return ASTPrinter.printSimple(type).trim();
        type.setArraySpecifier(null);
        String result = ASTPrinter.printSimple(type).trim();
        type.setArraySpecifier(array);
        return result;
    }

    private static @Nullable ArraySpecifier arrays(@Nullable ArraySpecifier outer, @Nullable ArraySpecifier inner) {
        if (outer == null) return inner;
        if (inner == null) return outer;
        return outer.getRoot().indexNodes(() -> new ArraySpecifier(
                Stream.concat(outer.getDimensions().stream(), inner.getDimensions().stream())
                      .map(value -> value == null ? null : value.clone())));
    }

    private static int components(String type) {
        if (type.equals("float") || type.equals("int") || type.equals("uint")) return 1;
        if (type.matches("[iu]?vec[234]")) return type.charAt(type.length() - 1) - '0';
        throw new IllegalStateException("Mesh varying type is not yet supported: " + type);
    }

    private static String vectorType(String type) {
        return type.equals("int") || type.startsWith("ivec") ? "ivec4"
                : type.equals("uint") || type.startsWith("uvec") ? "uvec4" : "vec4";
    }

    private static String decoder(Stage vertex) {
        Map<String, VertexFormatElement> elements = new LinkedHashMap<>();
        for (var element : TerrainVertexFormat.current().getElements()) elements.put(element.name(), element);
        StringBuilder out = new StringBuilder("void _flw_loadVertex(uint index) {\n")
                .append("    _FlwGuestVertex raw = _flw_geometry[index];\n")
                .append("    _flw_vertexId = int(index);\n    _flw_instanceId = 0;\n")
                .append("    _flw_baseVertex = int(_flw_sourceBaseVertex);\n    _flw_baseInstance = int(_flw_regionSlot);\n");
        for (Varying input : vertex.inputs) {
            VertexFormatElement element = elements.get(input.name());
            if (element == null) throw new IllegalStateException("Terrain format has no attribute " + input.name());
            var format = FORMAT.matcher(element.format().name());
            if (!format.matches()) throw new IllegalStateException("Unsupported terrain attribute format " + element);
            int channels = format.group(1).length();
            int bits = Integer.parseInt(format.group(2));
            String kind = format.group(3);
            out.append("    ").append(input.name()).append(" = ").append(input.type()).append('(');
            int width = components(input.type());
            for (int component = 0; component < width; component++) {
                if (component != 0) out.append(", ");
                if (component >= channels) {
                    out.append(component == 3 ? '1' : '0');
                    continue;
                }
                int byteOffset = element.offset() + component * bits / 8;
                String word = "raw.words[" + byteOffset / 4 + "]";
                int shift = byteOffset % 4 * 8;
                String unsigned = bits == 32 ? word : "bitfieldExtract(" + word + ", " + shift + ", " + bits + ")";
                String signed = bits == 32 ? "int(" + word + ")" : "bitfieldExtract(int(" + word + "), " + shift + ", " + bits + ")";
                out.append(switch (kind) {
                    case "UINT" -> unsigned;
                    case "SINT" -> signed;
                    case "UNORM" -> "(float(" + unsigned + ") / " + ((1L << bits) - 1) + ".0)";
                    case "SNORM" -> "max(float(" + signed + ") / " + ((1L << (bits - 1)) - 1) + ".0, -1.0)";
                    case "FLOAT" -> bits == 32 ? "uintBitsToFloat(" + word + ")"
                            : "unpackHalf2x16(" + word + ")." + (shift == 0 ? "x" : "y");
                    default -> throw new IllegalStateException(kind);
                });
            }
            out.append(");\n");
        }
        out.append("    uvec4 region = _flw_regionInput[_flw_regionSlot];\n")
           .append("    ivec3 origin = _flw_unpackRegionOrigin(region);\n");
        for (String region : vertex.regions) {
            out.append(region.equals("u_RegionOffset")
                    ? "    u_RegionOffset = vec3(origin * 16 - _flw_sodiumCameraInt) - _flw_sodiumCameraFrac;\n"
                    : "    u_RegionID = region.z;\n");
        }
        for (String initializer : vertex.initializers) out.append("    ").append(initializer).append('\n');
        return out.append("}\n").toString();
    }

    record Stages(@Nullable String task, String mesh, String fragment, int quads, int taskQuads, boolean taskRecovery,
                  boolean compactSafe) {
    }

    record Varying(String name, String type, String key, String interpolation) {
    }

    static final class Stage {
        final List<Varying> inputs = new ArrayList<>();
        final List<Varying> outputs = new ArrayList<>();
        final List<String> initializers = new ArrayList<>();
        final List<String> regions = new ArrayList<>();
        boolean globals;
        boolean sideEffects;
        boolean resourceReads;
        boolean depthWrites;
        boolean geometry;
        boolean drawIdSensitive;
        Map<Integer, String> fragmentOutputs = Map.of();
        Set<String> constants = Set.of();
        Map<String, String> sharedDeclarations = Map.of();
    }
}
