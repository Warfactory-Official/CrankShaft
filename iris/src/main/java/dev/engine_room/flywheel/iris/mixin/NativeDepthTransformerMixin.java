package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.transformer.DepthTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A native-depth pack keeps Minecraft's raw reversed depth: none of Iris's forward conversions apply.
 */
@Mixin(DepthTransformer.class)
abstract class NativeDepthTransformerMixin {
    @Inject(method = "injectForwardZProjection", at = @At("HEAD"), cancellable = true)
    private static void flywheel$rawProjection(ASTParser t, TranslationUnit tree, boolean _, CallbackInfo ci) {
        if (!NativeDepth.active()) return;
        tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "mat4 iris_undoRevZ(mat4 p) { return p; }");
        ci.cancel();
    }

    // Read inversion, the shadow clip-range rename and the fragment depth rewrites. Iris 1.11.7 adds the
    // texture-override overload, which its callers use directly.
    @Inject(method = {
            "transform(Lio/github/douira/glsl_transformer/ast/transform/ASTParser;Lio/github/douira/glsl_transformer/ast/node/TranslationUnit;Lio/github/douira/glsl_transformer/ast/query/Root;Lnet/irisshaders/iris/pipeline/transform/PatchShaderType;ZZ)V",
            "transform(Lio/github/douira/glsl_transformer/ast/transform/ASTParser;Lio/github/douira/glsl_transformer/ast/node/TranslationUnit;Lio/github/douira/glsl_transformer/ast/query/Root;Lnet/irisshaders/iris/pipeline/transform/PatchShaderType;ZZLjava/util/Set;)V"
    }, at = @At("HEAD"), cancellable = true, require = 1)
    private static void flywheel$rawDepth(CallbackInfo ci) {
        if (NativeDepth.active()) ci.cancel();
    }

    @Inject(method = "transformPosition", at = @At("HEAD"), cancellable = true)
    private static void flywheel$rawPosition(CallbackInfo ci) {
        if (NativeDepth.active()) ci.cancel();
    }
}
