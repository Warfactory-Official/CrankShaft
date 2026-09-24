package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compat with Better Block Entities: its per-block-entity "supported" flag gates baking into terrain meshes and its
 * extraction and tick managers. A block entity the engine visualizes is the engine's while a backend is on (Sodium
 * meshing threads read this too).
 */
@Mixin(value = BlockEntity.class, priority = 1100)
abstract class BetterBlockEntitiesMixin {
    @Dynamic("Added by Better Block Entities' BlockEntityMixin")
    @Inject(method = "bbe$isSupportedBlockEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$yieldToVisual(CallbackInfoReturnable<Boolean> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (VisualizationManager.supportsVisualization(self.getLevel()) && VisualizationHelper.skipVanillaRender(self)) {
            cir.setReturnValue(false);
        }
    }
}
