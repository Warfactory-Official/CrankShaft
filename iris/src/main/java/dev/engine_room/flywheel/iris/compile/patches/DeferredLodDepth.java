package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.engine.FarFrontDepth;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.EqualityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.InequalityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ReturnStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

import java.util.Set;
import java.util.HashSet;

import io.github.douira.glsl_transformer.ast.node.expression.Expression;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Canonical material-list depths for native main/LoD projection switches. Sky is outside both finite domains.
 */
final class DeferredLodDepth {
    private static final Set<String> DEPTHS = Set.of("depth", "waterDepth", "solidDepth", "targetSolidDepth",
            "refractionWaterDepth", "refractionSolidDepth");
    private static final Set<String> DECODERS = Set.of("GetGBufferData", "GetGBufferDataTransparent");
    private static final Set<String> READS = Set.of("texture", "textureLod", "texture2DLod", "texelFetch");
    /**
     * Sky as a depth texture holds it.
     */
    private static final String SKY_READ = "(_FLW_REVERSED_DEPTH?0.0:1.0)";
    /**
     * LoD sky, beyond every canonical depth, including LoD surfaces past the main far plane.
     */
    private static final String LOD_SKY = "(_FLW_REVERSED_DEPTH?-1.0:2.0)";
    private static final String NO_FRONT = "(_FLW_REVERSED_DEPTH?" + FarFrontDepth.REVERSED_NONE + ":"
            + FarFrontDepth.NONE + ")";

    private DeferredLodDepth() {
    }

    /**
     * {@code reversed}: the pack's own sky tests are spelled in raw reversed depth (sky 0).
     */
    static String sundial(String source, boolean reversed) {
        return normalize(source, false, reversed);
    }

    /**
     * {@code reversed}: the pack's own sky tests are spelled in raw reversed depth (sky 0).
     */
    static String seus(String source, boolean reversed) {
        return normalize(source, true, reversed);
    }

    private static String normalize(String source, boolean packed, boolean reversed) {
        String skyLiteral = reversed ? "0" : "1";
        // Matched against printed literals: Double.toString plus the float suffix.
        String thresholdLiteral = reversed ? "(?:0(?:\\.0*)?|1\\.0E-[4-9])f?" : "(?:1(?:\\.0*)?|0\\.9999[0-9]*)f?";
        return transform(source, (tree, root, parser) -> {
            if (!root.identifierIndex.has(packed ? "GetViewPositionLod" : "screenToViewDepthLod")) return;
            Set<FunctionCallExpression> nativeSelectors = new HashSet<>();
            for (var selection : root.nodeIndex.getStream(SelectionStatement.class).toList()) {
                if (!selection.hasAncestor(tree) || packed && nativeDecoder(
                        selection.getAncestor(FunctionDefinition.class))) continue;
                var functions = root.nodeIndex.getStream(FunctionCallExpression.class)
                                              .filter(call -> call.hasAncestor(
                                                      selection.getIfTrue()) && call.getFunctionName() != null)
                                              .map(call -> call.getFunctionName().getName()).toList();
                if (!functions.contains("getLodDepthWater") && !functions.contains("getLodDepthSolid")) continue;
                if (packed) {
                    var lodReads = calls(selection.getIfTrue(), root, "getLodDepthWater");
                    boolean opaque = lodReads.isEmpty();
                    if (opaque) lodReads = calls(selection.getIfTrue(), root, "getLodDepthSolid");
                    String coord = ASTPrinter.printSimple(lodReads.getFirst().getParameters().getFirst());
                    for (var comparison : root.nodeIndex.getStream(EqualityExpression.class).toList()) {
                        if (!comparison.hasAncestor(selection.getCondition())) continue;
                        String variable = ASTPrinter.printSimple(comparison.getLeft()).strip();
                        if (!DEPTHS.contains(variable.substring(variable.lastIndexOf('.') + 1))) continue;
                        String literal = ASTPrinter.printSimple(comparison.getRight()).strip();
                        if (!literal.matches(skyLiteral + "(?:\\.0*)?f?")) continue;
                        boolean solid = opaque || variable.toLowerCase().contains("solid");
                        Expression nativeCondition = parser.parseExpression(root, "textureLod("
                                + (solid ? "depthtex1" : "depthtex0") + "," + coord + ",0).x==" + SKY_READ);
                        comparison.replaceByAndDelete(nativeCondition);
                        for (var read : root.nodeIndex.getStream(FunctionCallExpression.class).toList())
                            if (read.hasAncestor(nativeCondition)) nativeSelectors.add(read);
                    }
                    continue;
                }
                String condition = ASTPrinter.printSimple(selection.getCondition()).replaceAll("\\s", "");
                if (!DEPTHS.stream().anyMatch(depth -> condition.matches("(?:[A-Za-z_]\\w*\\.)?" + depth + "=="
                        + skyLiteral + "(?:\\.0)?f?"))) continue;
                if (selection.hasIfFalse()) selection.replaceByAndDelete(selection.getIfFalse().cloneInto(root));
                else selection.detachAndDelete();
            }
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (!call.hasAncestor(tree) || call.getFunctionName() == null || nativeSelectors.contains(call))
                    continue;
                String name = call.getFunctionName().getName();
                var owner = call.getAncestor(FunctionDefinition.class);
                if (owner == null || owner.getFunctionPrototype().getName().getName().startsWith("getLodDepth")
                        || packed && nativeDecoder(owner)) continue;
                if (READS.contains(name) && call.getParameters().size() >= 2) {
                    String sampler = ASTPrinter.printSimple(call.getParameters().getFirst()).strip();
                    if (!sampler.equals("depthtex0") && !sampler.equals("depthtex1")) continue;
                    String coord = ASTPrinter.printSimple(call.getParameters().get(1));
                    if (name.equals("texelFetch")) coord = "(vec2(" + coord + ")+0.5)/screenSize";
                    String original = ASTPrinter.printSimple(call);
                    call.replaceByAndDelete(parser.parseExpression(root, "vec4(_flw_lodDepth(" + coord + ",("
                            + original + ").r," + sampler.equals("depthtex1") + "))"));
                } else if (name.equals("screenToViewPos"))
                    call.getFunctionName().setName("_flw_lodView");
                else if (packed && name.equals("GetViewPosition"))
                    call.getFunctionName().setName("_flw_lodView");
                else if (name.equals("screenToViewDepth"))
                    call.getFunctionName().setName("_flw_lodViewDepth");
            }
            for (var comparison : root.nodeIndex.getStream(BinaryExpression.class).toList()) {
                if (!comparison.hasAncestor(tree) || packed && nativeDecoder(
                        comparison.getAncestor(FunctionDefinition.class))) continue;
                String variable = ASTPrinter.printSimple(comparison.getLeft()).strip();
                if (!DEPTHS.contains(variable.substring(variable.lastIndexOf('.') + 1))) continue;
                String literal = ASTPrinter.printSimple(comparison.getRight()).strip();
                if (!literal.matches(thresholdLiteral)) continue;
                boolean farther = reversed
                        ? comparison instanceof LessThanExpression || comparison instanceof LessThanEqualExpression
                        : comparison instanceof GreaterThanExpression || comparison instanceof GreaterThanEqualExpression;
                boolean nearer = reversed
                        ? comparison instanceof GreaterThanExpression || comparison instanceof GreaterThanEqualExpression
                        : comparison instanceof LessThanExpression || comparison instanceof LessThanEqualExpression;
                boolean sky = comparison instanceof EqualityExpression || farther;
                boolean surface = comparison instanceof InequalityExpression || nearer;
                if (!sky && !surface) continue;
                comparison.replaceByAndDelete(parser.parseExpression(root, variable + (sky ? "==" : "!=") + LOD_SKY));
            }
            if (packed) {
                for (var exit : root.nodeIndex.getStream(ReturnStatement.class).toList()) {
                    if (!exit.hasAncestor(tree)) continue;
                    var owner = exit.getAncestor(FunctionDefinition.class);
                    if (!nativeDecoder(owner)) continue;
                    String result = ASTPrinter.printSimple(exit.getExpression()).strip();
                    if (!result.matches("[A-Za-z_]\\w*")) throw unsupported("LoD decoder return");
                    String coord = owner.getFunctionPrototype().getParameters().getFirst().getName().getName();
                    boolean opaque = owner.getFunctionPrototype().getName().getName().equals("GetGBufferData");
                    exit.replaceByAndDelete(parser.parseStatement(root, "{ " + result + ".depth=_flw_lodDepth("
                            + coord + ",texture2DLod(" + (opaque ? "depthtex1" : "depthtex0") + "," + coord
                            + ",0).x," + opaque + "); return " + result + "; }"));
                }
            }
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "float _flw_lodDepth(vec2 coord,float depth,bool opaque);",
                    (packed ? "vec4" : "vec3") + " _flw_lodView(vec2 coord,float depth);",
                    "float _flw_lodViewDepth(float depth);");
            ShaderDeclarations.uniform(tree, root, parser, "vec2", "screenSize");
            ShaderDeclarations.uniform(tree, root, parser, "sampler2D", "flw_farFrontDepth");
            if (packed) ShaderDeclarations.uniform(tree, root, parser, "mat4", "gbufferProjection");
            inject(tree, parser, """
                    float _flw_publishedFront(vec2 coord) {
                        ivec2 pixel=ivec2(floor(coord*screenSize));
                        if(any(lessThan(pixel,ivec2(0)))
                                ||any(greaterThanEqual(pixel,textureSize(flw_farFrontDepth,0)))) return _FLW_NO_FRONT;
                        return texelFetch(flw_farFrontDepth,pixel,0).r;
                    }
                    """.replace("_FLW_NO_FRONT", NO_FRONT), ASTInjectionPoint.END);
            inject(tree, parser, (packed ? """
                    float _flw_lodDepth(vec2 coord,float depth,bool opaque) {
                        if(depth!=_FLW_SKY_READ) return depth;
                        float lod=opaque?getLodDepthSolid(coord):getLodDepthWater(coord);
                        if(_FLW_REVERSED_DEPTH?lod<=0.0:lod>=1.0) return _FLW_LOD_SKY;
                        if(!opaque) {
                            float front=_flw_publishedFront(coord);
                            if(front!=_FLW_NO_FRONT) return front;
                        }
                        vec4 view=GetViewPositionLod(coord,lod);
                        vec4 clip=gbufferProjection*view;
                        return _FLW_CANONICAL_DEPTH;
                    }
                    vec4 _flw_lodView(vec2 coord,float depth) {
                        return GetViewPosition(coord,depth==_FLW_LOD_SKY?_FLW_SKY_READ:depth);
                    }
                    """.replace("_FLW_CANONICAL_DEPTH", DeferredHostShaders.canonicalDepth(reversed, "clip.z/clip.w"))
                    : """
                    float _flw_lodDepth(vec2 coord,float depth,bool opaque) {
                        if(depth!=_FLW_SKY_READ) return depth;
                        float lod=opaque?getLodDepthSolid(coord):getLodDepthWater(coord);
                        if(lod==_FLW_SKY_READ) return _FLW_LOD_SKY;
                        if(!opaque) {
                            float front=_flw_publishedFront(coord);
                            if(front!=_FLW_NO_FRONT) return front;
                        }
                        return viewToScreenDepth(screenToViewDepthLod(lod));
                    }
                    vec3 _flw_lodView(vec2 coord,float depth) {
                        return screenToViewPos(coord,depth==_FLW_LOD_SKY?_FLW_SKY_READ:depth);
                    }
                    float _flw_lodViewDepth(float depth) {
                        return screenToViewDepth(depth==_FLW_LOD_SKY?_FLW_SKY_READ:depth);
                    }
                    """).replace("_FLW_SKY_READ", SKY_READ).replace("_FLW_LOD_SKY", LOD_SKY)
                        .replace("_FLW_NO_FRONT", NO_FRONT), ASTInjectionPoint.END);
        });
    }

    private static boolean nativeDecoder(FunctionDefinition function) {
        return function != null && DECODERS.contains(function.getFunctionPrototype().getName().getName());
    }

    static String depth(Root root, String coord, String read, boolean opaque) {
        return root.identifierIndex.has("_flw_lodDepth")
                ? "_flw_lodDepth(" + coord + "," + read + "," + opaque + ")" : read;
    }

    static String opaque(Root root, String pixel) {
        String read = "texelFetch(depthtex1," + pixel + ",0).r";
        return root.identifierIndex.has("_flw_lodDepth")
                ? "_flw_lodDepth((vec2(" + pixel + ")+0.5)/screenSize," + read + ",true)" : read;
    }

    static String front(Root root, String pixel) {
        String read = "texelFetch(depthtex0," + pixel + ",0).r";
        return root.identifierIndex.has("_flw_lodDepth")
                ? "_flw_lodDepth((vec2(" + pixel + ")+0.5)/screenSize," + read + ",false)" : read;
    }
}
