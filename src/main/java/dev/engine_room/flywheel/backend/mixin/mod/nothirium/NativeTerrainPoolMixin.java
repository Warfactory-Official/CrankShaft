package dev.engine_room.flywheel.backend.mixin.mod.nothirium;

import dev.engine_room.flywheel.backend.engine.shaderpack.NothiriumTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "meldexun.nothirium.opengl.DynamicVBO", remap = false)
public abstract class NativeTerrainPoolMixin {
    // Buffer names may be recycled while the disposed pool's parts still report themselves valid.
    @Inject(method = "dispose", at = @At("HEAD"), require = 1)
    private void flw$retire(CallbackInfo ci) { NothiriumTerrain.retireBuffers(); }
}
