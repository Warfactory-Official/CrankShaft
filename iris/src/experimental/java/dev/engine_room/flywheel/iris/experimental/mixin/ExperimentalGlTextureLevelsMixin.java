package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import org.lwjgl.opengl.GL12C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.irisshaders.iris.opengl.GlShaderBindings")
abstract class ExperimentalGlTextureLevelsMixin {
    @Redirect(method = "bind", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_texParameter(III)V"))
    private void flywheel$dedupMipLevels(int target, int pname, int value, @Local TextureBinding.Resolved texture) {
        GlTexture gl = ((GlTextureView) texture.texture()).texture();
        if (pname == GL12C.GL_TEXTURE_BASE_LEVEL ? GlTextureLevelState.shouldApplyBase(gl, value)
                : GlTextureLevelState.shouldApplyMax(gl, value)) {
            GlStateManager._texParameter(target, pname, value);
        }
    }
}
