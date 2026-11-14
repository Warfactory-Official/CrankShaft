package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.common.render.openGl.GlDhTerrainRenderer;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.impl.compat.NativeWaterRenderer;
import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GlDhTerrainRenderer.class)
abstract class DhGlWaterRendererMixin implements NativeWaterRenderer {
    @Shadow
    public abstract GlDhTerrainShaderProgram getTerrainShaderProgram();

    @Override
    public boolean flywheel$waterReady() {
        return getTerrainShaderProgram().flywheel$waterReady();
    }

    @Override
    public NativeWaterShaders flywheel$waterShaders() {
        return getTerrainShaderProgram().flywheel$waterShaders();
    }

    @Override
    public void flywheel$prepareWater(RenderParams params) {
        getTerrainShaderProgram().flywheel$prepareWater(params);
    }

    @Override
    public void flywheel$finishWater() {
    }
}
