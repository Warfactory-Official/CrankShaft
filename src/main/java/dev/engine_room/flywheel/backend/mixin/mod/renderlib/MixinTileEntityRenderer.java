package dev.engine_room.flywheel.backend.mixin.mod.renderlib;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import meldexun.renderlib.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityRenderer.class)
public abstract class MixinTileEntityRenderer {

    @Inject(method = "setup", at = @At("HEAD"), require = 1, remap = false)
    private void flw$cacheSupports(ICamera frustum, float partialTicks, double camX, double camY, double camZ, CallbackInfo ci) {
        VisualizationHelper.cacheSupportsVisualization(Minecraft.getMinecraft().world);
    }

    @Dynamic
    @Redirect(method = "shouldRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/tileentity/TileEntity;shouldRenderInPass(I)Z",
                    ordinal = 0,
                    remap = false),
            require = 1,
            remap = false)
    private boolean flw$skipVisualizedTE(TileEntity te, int pass) {
        if (VisualizationHelper.shouldSkipTileEntity(te)) {
            return false;
        }
        return te.shouldRenderInPass(pass);
    }
}
