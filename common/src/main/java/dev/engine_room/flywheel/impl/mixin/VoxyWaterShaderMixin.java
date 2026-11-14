package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import dev.engine_room.flywheel.impl.compat.NativeWaterSource;
import me.cortex.voxy.client.core.gl.shader.Shader;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Shader.class)
abstract class VoxyWaterShaderMixin implements NativeWaterSource {
    @Shadow
    public abstract int id();

    @Unique
    private String flywheel$vertex;
    @Unique
    private String flywheel$fragment;
    @Unique
    private @Nullable NativeWaterShaders flywheel$water;

    @Override
    public void flywheel$waterSources(String vertex, String fragment) {
        flywheel$vertex = vertex;
        flywheel$fragment = fragment;
    }

    @Override
    public NativeWaterShaders flywheel$waterShaders() {
        if (flywheel$water == null) {
            flywheel$water = new NativeWaterShaders("voxy/water", id(), flywheel$vertex, flywheel$fragment,
                    "outColour");
        }
        return flywheel$water;
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void flywheel$deleteWater(CallbackInfo ci) {
        NativeLodWater.releaseVoxy(this);
        if (flywheel$water != null) {
            flywheel$water.close();
            flywheel$water = null;
        }
    }
}
