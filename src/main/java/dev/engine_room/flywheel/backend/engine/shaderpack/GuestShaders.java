package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestShaderTemplates;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslInterface;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Map;
import java.util.Set;

final class GuestShaders {
    private static final String[] LEGACY_OUTPUTS = {
            "_flw_packOutput[0]", "_flw_packOutput[1]", "_flw_packOutput[2]", "_flw_packOutput[3]",
            "_flw_packOutput[4]", "_flw_packOutput[5]", "_flw_packOutput[6]", "_flw_packOutput[7]"
    };
    private static final Set<String> READ_ONLY_STORAGE = Set.of("_FlwGuestInstances", "_FlwGuestTypeBases",
            "_FlwGuestVisibleInstances", "_FlwGuestDraws", "_FlwGuestMatrices", "_FlwTerrainVertices", "_FlwTerrainDraws");

    private GuestShaders() { }

    static String versionPreamble() { return GuestShaderTemplates.HEADER; }
    static String libraryPreamble() { return ""; }

    static void checkStageEffects(String stage) { checkEffects(GlslAst.parse(stage), true); }

    private static void checkEffects(GlslAst ast, boolean guestStorage) {
        var storage = ast.nodes(StorageQualifier.class).anyMatch(qualifier -> {
            if (qualifier.storageType != StorageType.BUFFER) return false;
            var block = qualifier.getAncestor(InterfaceBlockDeclaration.class);
            return !guestStorage || !READ_ONLY_STORAGE.contains(block.getBlockName().getName())
                    || !GlslAst.storage(block.getTypeQualifier(), StorageType.READONLY);
        });
        var writes = ast.nodes(FunctionCallExpression.class).anyMatch(call -> {
            var name = call.getFunctionName();
            return name != null && (name.getName().equals("imageStore") || name.getName().startsWith("imageAtomic")
                    || name.getName().startsWith("atomic"));
        });
        if (storage || writes || ast.references("gl_FragDepth"))
            throw new UnsupportedOperationException("OIT stage has depth/storage side effects");
    }

    static void checkNativeEffects(GuestRenderPass pass) {

        if (!GlCompat.CAPABILITIES.OpenGL43 && !GlCompat.CAPABILITIES.GL_ARB_program_interface_query) {

            throw new UnsupportedOperationException("Native OIT eligibility needs program interface queries");

        }

        // Native source retains inactive preprocessor branches; inspect the linked program's live storage resources.

        if (GL43.glGetProgramInterfacei(pass.program, GL43.GL_SHADER_STORAGE_BLOCK, GL43.GL_ACTIVE_RESOURCES) != 0

                || GL20.glGetProgrami(pass.program, GL42.GL_ACTIVE_ATOMIC_COUNTER_BUFFERS) != 0) {

            throw new UnsupportedOperationException(
                    "Native world program has active storage buffers or atomic counters");

        }

        try (var stack = MemoryStack.stackPush()) {

            var size = stack.mallocInt(1);

            var type = stack.mallocInt(1);

            var count = GL20.glGetProgrami(pass.program, GL20.GL_ACTIVE_UNIFORMS);

            var length = GL20.glGetProgrami(pass.program, GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH);

            for (var index = 0; index < count; index++) {

                GL20.glGetActiveUniform(pass.program, index, length, size, type);

                if (type.get(0) >= 0x904C && type.get(0) <= 0x906C) {

                    throw new UnsupportedOperationException("Native world program has active image uniforms");

                }

            }

        }

    }


    static Demoted demote(String fragment) {
        var ast = GlslAst.parse(fragment);
        checkEffects(ast, false);
        var coordinates = GlslInterface.fragmentCoordinates(ast);
        var outputs = GlslInterface.fragmentOutputs(ast);
        for (var access : ast.nodes(ArrayAccessExpression.class).toList()) {
            if (!GlslAst.matches(access.getLeft(), "gl_FragData")) continue;
            if (!(access.getRight() instanceof LiteralExpression literal) || !literal.isInteger()
                    || literal.getInteger() < 0 || literal.getInteger() >= 8)
                throw new UnsupportedOperationException("OIT needs literal legacy output indices in 0..7");
            var slot = Math.toIntExact(literal.getInteger());
            var previous = outputs.put(slot, LEGACY_OUTPUTS[slot]);
            if (previous != null && !previous.equals(LEGACY_OUTPUTS[slot]))
                throw new UnsupportedOperationException("OIT fragment mixes legacy and declared outputs");
        }
        if (ast.references("gl_FragColor")) {
            var previous = outputs.put(0, LEGACY_OUTPUTS[0]);
            if (previous != null && !previous.equals(LEGACY_OUTPUTS[0]))
                throw new UnsupportedOperationException("OIT fragment mixes legacy and declared outputs");
        }
        if (outputs.isEmpty()) throw new UnsupportedOperationException("OIT fragment has no supported color outputs");
        return new Demoted(ast.source(), outputs, coordinates.upperLeft(), coordinates.integerCenter());
    }

    static String fragment(String original, OitSpec spec, GuestHost owner) {
        var demoted = owner.demote(original);
        var outputs = demoted.outputs();
        var ast = GlslAst.preprocessed(demoted.source());
        ast.renameFunction("main", "_flw_irisMain");
        ast.references(Map.of("gl_FragData", "_flw_packOutput", "gl_FragColor", "_flw_packOutput[0]",
                "gl_FragCoord", "_flw_packFragCoord()"));
        var source = new StringBuilder(versionPreamble());
        source.append(ast.extensions());
        source.append("vec4 _flw_packOutput[8];\nuniform mat4 _flw_oitProjectionInverse;\nuniform vec2 _flw_oitViewportSize;\nuniform vec2 _flw_oitViewportOrigin;\nuniform float _flw_oitSourceHeight;\n");
        // Complementary initializes texelCoord globally, before the wrapper's main can run.
        source.append("vec4 _flw_packFragCoord() {\nvec4 coord=gl_FragCoord;coord.xy+=_flw_oitViewportOrigin;\n");
        if (demoted.upperLeft()) source.append("coord.y=_flw_oitSourceHeight-coord.y;\n");
        if (demoted.integerCenter()) source.append("coord.xy-=vec2(0.5);\n");
        source.append("return coord;\n}\n");
        source.append("""
                uniform int _flw_nativeAlphaFunction;
                uniform float _flw_nativeAlphaReference;
                bool _flw_nativeAlphaPassed(float a) {
                    if (_flw_nativeAlphaFunction == 512) return false;
                    if (_flw_nativeAlphaFunction == 513) return a < _flw_nativeAlphaReference;
                    if (_flw_nativeAlphaFunction == 514) return a == _flw_nativeAlphaReference;
                    if (_flw_nativeAlphaFunction == 515) return a <= _flw_nativeAlphaReference;
                    if (_flw_nativeAlphaFunction == 516) return a > _flw_nativeAlphaReference;
                    if (_flw_nativeAlphaFunction == 517) return a != _flw_nativeAlphaReference;
                    if (_flw_nativeAlphaFunction == 518) return a >= _flw_nativeAlphaReference;
                    return true;
                }
                """);
        source.append(ast.body()).append('\n').append(GuestOitCodegen.library()).append('\n');
        var producer = GlslAst.preprocessed(GuestOitCodegen.producer(spec, outputs));
        var calls = producer.calls("_flw_irisMain").toList();
        if (calls.size() != 1) throw new IllegalStateException("OIT producer needs one native colour invocation");
        producer.insertAfter(calls.getFirst().getAncestor(ExpressionStatement.class), """
                if (!_flw_nativeAlphaPassed((%s).a)) discard;
                vec4 _flw_depthPosition = _flw_oitProjectionInverse * vec4(gl_FragCoord.xy / _flw_oitViewportSize * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
                float _flw_oitViewZ = _flw_depthPosition.z / _flw_depthPosition.w;
                """.formatted(outputs.getOrDefault(0, "vec4(1.0)")));
        return source.append(producer.body()).toString();
    }

    record OitSpec(OitPass pass, GuestOitContract oit, int[] drawBuffers, boolean shadow) { }
    record Demoted(String source, Int2ObjectMap<String> outputs, boolean upperLeft, boolean integerCenter) { }
}
