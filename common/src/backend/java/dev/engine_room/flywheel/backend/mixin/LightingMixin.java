package dev.engine_room.flywheel.backend.mixin;

import com.mojang.blaze3d.platform.Lighting;
import dev.engine_room.flywheel.backend.engine.uniform.LevelUniforms;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2 moved the level light seam to Lighting#updateBuffer; grab the untransformed vectors
// here so flw_light*Direction tracks whatever vanilla/mods actually set.
// remap=false: blaze3d names survive unchanged on both loaders.
@Mixin(value = Lighting.class, remap = false)
abstract class LightingMixin {
    @Inject(method = "updateBuffer", at = @At("HEAD"), require = 1)
    private void flywheel$onUpdateLevelLight(Lighting.Entry entry, Vector3fc light0, Vector3fc light1,
                                             CallbackInfo ci) {
        if (entry == Lighting.Entry.LEVEL) {
            LevelUniforms.LIGHT0_DIRECTION.set(light0);
            LevelUniforms.LIGHT1_DIRECTION.set(light1);
        }
    }
}
