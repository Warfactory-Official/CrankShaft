package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.extension.TileEntityExtension;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineShadowEntityCulling", remap = false)
public abstract class ShadowEntityCullingMixin {
    // AUSM calls the coordinate-based dispatcher overload, bypassing the usual visualized-block-entity guard.
    @Inject(method = "shouldRenderBlockEntityInShadowMap", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$skipVisualized(World world, TileEntity entity, BlockPos pos, ICamera camera,
                                    double x, double y, double z, double distanceSquared,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (VisualizationManager.supportsVisualization(world) && ((TileEntityExtension) entity).flw$skipVanillaRender())
            cir.setReturnValue(false);
    }
}
