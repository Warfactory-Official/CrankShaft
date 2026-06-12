package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.sodium.TerrainCullGate;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Sodium's per-frame render-list materialization when the engine's GPU-driven terrain takeover is live.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private boolean flywheel$cancelledLastCall;

    @Inject(method = "finalizeRenderLists", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$cancelRenderListBuild(CallbackInfo ci) {
        if (!TerrainCullGate.shouldCancelSodiumCull()) {
            if (flywheel$cancelledLastCall) {
                flywheel$cancelledLastCall = false;
                ((RenderSectionManagerAccessor) (Object) this).flywheel$setNeedsRenderListUpdate(true);
            }
            return;
        }
        flywheel$cancelledLastCall = true;
        RenderSectionManagerAccessor self = (RenderSectionManagerAccessor) (Object) this;
        self.flywheel$setRenderLists(SortedRenderLists.empty());
        self.flywheel$setNeedsRenderListUpdate(false);
        self.flywheel$setCameraChanged(false);
        ci.cancel();
    }
}
