package dev.engine_room.flywheel.impl.mixin;

import net.minecraft.client.renderer.block.BlockModelSet;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code Models.displayBlock} resolves through {@code get} from visual-creation workers while the render thread
 * extracts entities through it.
 */
@Mixin(BlockModelSet.class)
abstract class BlockModelSetMixin {
    @Shadow
    @Final
    @Mutable
    private Map<BlockState, BlockModel> blockModelByStateCache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flywheel$concurrentCache(CallbackInfo ci) {
        blockModelByStateCache = new ConcurrentHashMap<>(blockModelByStateCache);
    }
}
