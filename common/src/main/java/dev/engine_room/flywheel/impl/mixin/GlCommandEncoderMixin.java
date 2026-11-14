package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
#if MC < 26.3
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
#endif

@Mixin(GlCommandEncoder.class)
abstract class GlCommandEncoderMixin {
    private static final int GL_TEXTURE_BASE_LEVEL = 33084;
    private static final int GL_TEXTURE_MAX_LEVEL = 33085;

    #if MC < 26.3
    @ModifyConstant(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            constant = @Constant(intValue = 5121), require = 1)
    private int flywheel$uploadPixelType(int _, @Local(argsOnly = true) GpuTexture destination) {
        return GlConst.toGlType(destination.getFormat());
    }

    @ModifyExpressionValue(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/GpuFormat;componentCount()I"), require = 1)
    private int flywheel$uploadAlignment(int _, @Local(argsOnly = true) GpuTexture destination) {
        return destination.getFormat().byteAlignment();
    }
    #endif

    #if MC >= 26.3
    @Redirect(method = "setupDraw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_texParameter(III)V"))
    #else
    @Redirect(method = "trySetup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"))
    #endif
    private void flywheel$dedupMipLevels(int target, int pname, int value, @Local GlTexture texture) {
        if (pname == GL_TEXTURE_BASE_LEVEL && !GlTextureLevelState.shouldApplyBase(texture, value)) {
            return;
        }
        if (pname == GL_TEXTURE_MAX_LEVEL && !GlTextureLevelState.shouldApplyMax(texture, value)) {
            return;
        }
        GlStateManager._texParameter(target, pname, value);
    }
}
