package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.lib.util.TerrainReceivers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorReceiverMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void flywheel$retireReceivers(ClientLevel next, CallbackInfo _) {
        if (level != null && level != next) TerrainReceivers.clear(level);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"))
    private void flywheel$invalidateReceiver(BlockPos position, BlockState previous, BlockState current,
                                             CallbackInfo _) {
        if (level != null) TerrainReceivers.invalidate(level, position, previous, current);
    }
}
