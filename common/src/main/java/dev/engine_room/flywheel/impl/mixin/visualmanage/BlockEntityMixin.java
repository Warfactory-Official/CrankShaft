package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class BlockEntityMixin {
    @Shadow
    @Nullable
    protected Level level;

    @Inject(method = "setRemoved()V", at = @At("TAIL"), require = 1)
    private void flw$removeVisual(CallbackInfo ci) {
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return;
        }
        manager.blockEntities()
                .queueRemove((BlockEntity) (Object) this);
    }
}
