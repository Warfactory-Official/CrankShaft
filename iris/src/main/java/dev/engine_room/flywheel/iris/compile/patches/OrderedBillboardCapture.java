package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

import java.util.regex.Pattern;

/** Orders only linked-head publication; each invocation writes its exclusive node after the critical section. */
public final class OrderedBillboardCapture {
    private static final Pattern HELPER_RETURN = Pattern.compile(
            "if\\s*\\(\\s*gl_HelperInvocation\\s*\\)\\s*(?:return\\s*;|\\{\\s*return\\s*;\\s*\\})");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");

    private OrderedBillboardCapture() {
    }

    public static boolean enabled() {
        return !IrisRuntime.EXPERIMENTAL && !VkContext.isVulkanHost() && GlCompat.SUPPORTS_FRAGMENT_INTERLOCK
                && Boolean.parseBoolean(System.getProperty("crankshaft.iris.orderedBillboardLayers", "true"));
    }

    public static String apply(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            var main = tree.getOneMainDefinitionBody();
            var capture = DeferredSources.calls(main, root, "flw_captureLight").getFirst();
            String pixel = ASTPrinter.printSimple(capture.getParameters().get(0));
            String depth = ASTPrinter.printSimple(capture.getParameters().get(1));
            String value = ASTPrinter.printSimple(capture.getParameters().get(2));
            String size = ASTPrinter.printSimple(capture.getParameters().get(3));
            String late = ASTPrinter.printSimple(capture.getParameters().get(4));
            var captureCondition = capture.getAncestor(SelectionStatement.class);
            String predicate = ASTPrinter.printSimple(captureCondition.getCondition());
            var statements = main.getStatements();
            var lighting = DeferredSources.calls(main, root, "_flw_lightMain").getFirst().getAncestor(Statement.class);
            String prefix = DeferredSources.print(statements.subList(0, statements.indexOf(lighting)));
            var prologue = new StringBuilder();
            for (var statement : statements.subList(statements.indexOf(lighting) + 1,
                    statements.indexOf(captureCondition))) {
                String text = ASTPrinter.printSimple(statement).strip();
                if (HELPER_RETURN.matcher(text).matches()) continue;
                if (RETURN.matcher(text).find())
                    throw new UnsupportedOperationException("Ordered billboard capture prologue returns before its interlock: "
                            + text);
                prologue.append(text).append('\n');
            }
            String suffix = DeferredSources.print(statements.subList(statements.indexOf(captureCondition) + 1,
                    statements.size()));
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "#extension GL_ARB_fragment_shader_interlock : require\n",
                    "layout(pixel_interlock_ordered) in;");
            main.replaceByAndDelete(parser.parseStatement(root, """
                    {
                        %s
                        _flw_lightMain();
                        %s
                        ivec2 flw_orderedPixel = %s;
                        uvec2 flw_orderedSize = %s;
                        bool flw_orderedLate = %s;
                        float flw_orderedDepth = %s;
                        vec4 flw_orderedValue = %s;
                        bool flw_orderedKeep = !gl_HelperInvocation && flw_oitActive
                                && !all(equal(flw_orderedValue, vec4(0.0)));
                        if (flw_orderedKeep && !(%s)) {
                            flw_orderedKeep = false;
                        }
                        if (flw_orderedKeep && flw_orderedLate
                                && !flw_lightDemanded(flw_orderedPixel, flw_orderedSize)) {
                            flw_orderedKeep = false;
                        }
                        uint flw_orderedNode = 0xffffffffu;
                        if (flw_orderedKeep) {
                            if (flw_orderedValue.a != 0.0 && flw_needsLight == 0u) flw_needsLight = 1u;
                            flw_orderedNode = flw_reserveSlots(1u);
                            if (flw_orderedNode >= uint(flw_nodes.length())) {
                                if (flw_overflow == 0u) flw_overflow = 1u;
                                flw_orderedKeep = false;
                            }
                        }
                        uint flw_orderedPrevious = 0xffffffffu;
                        uint flw_orderedOffset = uint(flw_orderedPixel.y) * flw_orderedSize.x + uint(flw_orderedPixel.x);
                        beginInvocationInterlockARB();
                        if (flw_orderedKeep) {
                            flw_orderedPrevious = atomicExchange(flw_heads[flw_orderedOffset], flw_orderedNode);
                        }
                        endInvocationInterlockARB();
                        if (flw_orderedKeep) {
                            flw_nodes[flw_orderedNode] = uvec4(flw_orderedPrevious, floatBitsToUint(flw_orderedDepth),
                                    packHalf2x16(flw_orderedValue.rg), packHalf2x16(flw_orderedValue.ba));
                        }
                        %s
                    }
                    """.formatted(prefix, prologue, pixel, size, late, depth, value, predicate, suffix)));
        });
        return parser.transform(source);
    }
}
