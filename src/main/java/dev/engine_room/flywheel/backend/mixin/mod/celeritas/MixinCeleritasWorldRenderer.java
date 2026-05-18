package dev.engine_room.flywheel.backend.mixin.mod.celeritas;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

@Mixin(CeleritasWorldRenderer.class)
public abstract class MixinCeleritasWorldRenderer {

    @Dynamic
    @Inject(method = "renderBlockEntities", at = @At("HEAD"), require = 1, remap = false)
    private void flw$cacheSupports(CeleritasWorldRenderer.TileEntityRenderContext context,
                                   CallbackInfoReturnable<Integer> cir) {
        VisualizationHelper.cacheSupportsVisualization(Minecraft.getMinecraft().world);
    }

    @Dynamic
    @Inject(method = "renderBlockEntities", at = @At("TAIL"), require = 1, remap = false)
    private void flw$afterEntities(CeleritasWorldRenderer.TileEntityRenderContext context,
                                   CallbackInfoReturnable<Integer> cir) {
        VisualizationHelper.dispatchAfterEntities(Minecraft.getMinecraft().world);
    }

    @Dynamic
    @Redirect(method = "renderBlockEntityList",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/tileentity/TileEntity;shouldRenderInPass(I)Z",
                    remap = false),
            require = 1,
            remap = false)
    private boolean flw$skipVisualizedShouldRenderInPass(TileEntity te, int pass) {
        if (VisualizationHelper.shouldSkipTileEntity(te)) {
            return false;
        }
        return te.shouldRenderInPass(pass);
    }
}
