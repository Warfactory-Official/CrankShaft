package dev.engine_room.flywheel.backend.mixin.mod.optifine;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRendering;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VboRenderList.class)
public abstract class VboRenderListMixin {
    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$indirect(BlockRenderLayer layer, CallbackInfo ci) {
        if (layer != BlockRenderLayer.TRANSLUCENT && GuestRendering.renderNativeTerrain(layer)) ci.cancel();
    }
}
