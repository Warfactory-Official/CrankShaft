package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    private @Nullable Level level;

    // Farthest LoD point: horizontal radius x full level height.
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float flywheel$lodFar(float original) {
        int lod = LodCompat.observe();
        if (lod == 0 || level == null) {
            return original;
        }
        float height = level.getHeight();
        return Math.max(original, (float) Math.sqrt((double) lod * lod + height * height));
    }
}
