package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.opengl.GlRenderBackend;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A native-depth pack's hardware shadow comparisons follow its reversed shadow depth; the samplers are per pack.
 */
@Mixin(GlRenderBackend.GlPackResources.class)
abstract class ExperimentalNativeGlShadowSamplersMixin {
    @ModifyArg(method = "lambda$getSampler$0", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;samplerParameteri(III)V"), index = 2)
    private static int flywheel$reversedCompare(int _, int parameter, int value) {
        return parameter == GL14C.GL_TEXTURE_COMPARE_FUNC && NativeDepth.active() ? GL11C.GL_GEQUAL : value;
    }
}
