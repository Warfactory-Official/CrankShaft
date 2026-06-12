package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @Inject(method = "handleBlockEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$tryAddBlockEntity(@Coerce Object results, BlockEntity blockEntity, CallbackInfo ci) {
        if (VisualizationHelper.tryAddBlockEntity(blockEntity)) {
            ci.cancel();
        }
    }
}
