package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRenderPass;
import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRendering;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VboRenderList.class)
public abstract class VboRenderListMixin {
    // AUSM's shadow container draws directly; leave its selected VBOs for the shared guest replay.
    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$deferTranslucent(BlockRenderLayer layer, CallbackInfo ci) {
        if (layer == BlockRenderLayer.TRANSLUCENT && GuestRenderPass.shadowActive() && GuestRendering.replaysShadowTerrain()) {
            ci.cancel();
        } else if (layer != BlockRenderLayer.TRANSLUCENT && GuestRendering.renderNativeTerrain(layer)) {
            ci.cancel();
        }
    }
}
