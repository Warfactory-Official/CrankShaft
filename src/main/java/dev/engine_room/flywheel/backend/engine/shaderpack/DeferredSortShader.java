package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationExpression;

import java.util.stream.Collectors;

final class DeferredSortShader {
    enum Mode { SEUS, MATERIAL, LIGHT }

    private DeferredSortShader() { }

    static String build(Mode mode, String pixel, String visibility) {
        var source = DeferredReplayShaders.resource("layer_sort.glsl")
                .replace("_FLW_SORT_PIXEL", pixel).replace("_FLW_VISIBILITY_BODY", visibility)
                .replace("_FLW_SORT_COUNTS_LAYERS", Boolean.toString(mode != Mode.LIGHT))
                .replace("_FLW_SORT_LAYER_BIAS", "0u");
        var ast = GlslAst.preprocessed("void _flw_sortBody(){" + source + "}");
        var stores = ast.assignments("flw_nodes[flw_node*2u+1u].w");
        if (stores.size() != 1) throw new UnsupportedOperationException("Deferred sort density store changed");
        var store = stores.getFirst();
        if (mode == Mode.MATERIAL) store.value(ast.expression(
                "(floatBitsToUint(transparentDensity)&0x7fffffffu)|(flw_nodes[flw_node*2u+1u].w&FLW_LAYER_UNLIT)"));
        else store.statement().detachAndDelete();
        // These profiles store one uvec4 per list link; only scale node addresses, not arithmetic in native visibility code.
        for (var access : ast.nodes(ArrayAccessExpression.class).toList()) {
            if (!GlslAst.matches(access.getLeft(), "flw_nodes")) continue;
            var index = access.getRight();
            for (var product : ast.nodes(MultiplicationExpression.class)
                    .filter(node -> node == index || node.hasAncestor(index)).toList()) {
                if (GlslAst.matches(product.getRight(), "2u"))
                    product.replaceByAndDelete(product.getLeft().cloneInto(ast.tree.getRoot()));
            }
        }
        if (mode == Mode.LIGHT) {
            ast.rename("flw_heads", "flw_lightHeads");
            ast.renameFunction("flw_layerBefore", "flw_lightBefore");
        } else if (mode == Mode.SEUS) ast.replaceExpression("flw_length+0u", "flw_visible", true);
        return ast.function("_flw_sortBody").getBody().getStatements().stream()
                .map(GlslAst::print).collect(Collectors.joining("\n"));
    }
}
