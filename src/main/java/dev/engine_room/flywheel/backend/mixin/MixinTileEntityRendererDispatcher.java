package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Defensive backstop for mods that call dispatcher.render(3-arg) directly outside the
// RenderGlobal/Sodium/Celeritas filters CrankShaft hooks higher up (HBM LightRenderer, RenderLib,
// Kirino, Mekanism RenderIndustrialTurbine, etc.). 7-arg inject removed: it only fires from the
// 5/6-arg delegation chain used by mods like Quark's PistonTileEntityRenderer, where cancelling
// hides the TE during the piston push (Flywheel isn't tracking the transient pushed TE either).
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcher {

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void flw$skipVisualizedTileEntity3(TileEntity te, float partialTicks, int destroyStage,
                                                        CallbackInfo ci) {
        if (VisualizationManager.supportsVisualization(te.getWorld())
                && VisualizationHelper.skipVanillaRender(te)) {
            ci.cancel();
        }
    }
}
