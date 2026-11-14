package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import dev.engine_room.flywheel.impl.compat.NativeWaterRenderer;
import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlDhTerrainShaderProgram.class)
abstract class DhGlWaterShaderMixin implements NativeWaterRenderer {
    @Shadow
    private int frameIndexMod8;
    @Unique
    private @Nullable NativeWaterShaders flywheel$water;

    @Override
    public boolean flywheel$waterReady() {
        return flywheel$waterShaders().ready();
    }

    @Override
    public NativeWaterShaders flywheel$waterShaders() {
        var nativeProgram = (GlDhTerrainShaderProgram) (Object) this;
        nativeProgram.tryInit();
        if (flywheel$water == null) {
            flywheel$water = NativeWaterShaders.capture("dh/gl-water", nativeProgram.getId());
        }
        return flywheel$water;
    }

    @Override
    public void flywheel$prepareWater(RenderParams params) {
        int frame = frameIndexMod8;
        if (frame >= 0) frameIndexMod8 = (frame + 7) % 8;
        ((GlDhTerrainShaderProgram) (Object) this).fillUniformData(params.apiCopy);
        frameIndexMod8 = frame;
    }

    @Override
    public void flywheel$finishWater() {
    }

    // Native setup may reset blend/depth state; producer attachments and state belong to the shared OIT pass.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/lwjgl/ILWJGLService;glDrawElements(IIII)V"))
    private void flywheel$producerState(CallbackInfo ci) {
        NativeLodWater.bindGlDhProducer();
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void flywheel$deleteWater(CallbackInfo ci) {
        NativeLodWater.releaseDh();
        if (flywheel$water != null) {
            flywheel$water.close();
            flywheel$water = null;
        }
    }
}
