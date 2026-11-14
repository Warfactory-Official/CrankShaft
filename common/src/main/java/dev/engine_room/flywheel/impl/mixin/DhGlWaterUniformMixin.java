package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.lwjgl.ILWJGLService;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlShaderProgram.class)
abstract class DhGlWaterUniformMixin {
    @Shadow
    @Final
    public int id;

    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/lwjgl/ILWJGLService;glUseProgram(I)V"))
    private void flywheel$waterProgram(ILWJGLService _, int nativeProgram) {
        GlStateTracker.useProgram(NativeLodWater.program(nativeProgram));
    }

    @ModifyVariable(method = "setUniform", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int flywheel$waterUniform(int location) {
        return NativeLodWater.uniform(id, location);
    }
}
