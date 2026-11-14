package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import net.irisshaders.iris.opengl.GlRenderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlRenderBackend.class)
abstract class ExperimentalGlMipmapLevelsMixin {
    @Inject(method = "generateMipmaps", at = @At("TAIL"))
    private void flywheel$mipLevels(GpuTexture texture, boolean _, CallbackInfo _) {
        var state = (GlTextureLevelState) texture;
        state.flywheel$lastBase(0);
        state.flywheel$lastMax(texture.getMipLevels() - 1);
    }
}
