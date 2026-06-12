package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlCommandEncoder.class)
abstract class GlCommandEncoderMixin {
    private static final int GL_TEXTURE_BASE_LEVEL = 33084;
    private static final int GL_TEXTURE_MAX_LEVEL = 33085;

    @Redirect(method = "trySetup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"))
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
