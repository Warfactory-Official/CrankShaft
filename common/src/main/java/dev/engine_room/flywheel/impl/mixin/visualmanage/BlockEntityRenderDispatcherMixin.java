package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.visualization.ConcurrentExtraction;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
    // Every overload holding the call: NeoForge's Frustum variant carries it on NeoForge.
    @WrapOperation(method = "tryExtractRenderState*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;extractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"), require = 1)
    private void flw$deferBlockEntity(BlockEntityRenderer<?, ?> renderer, BlockEntity blockEntity,
                                      BlockEntityRenderState state, float partialTicks, Vec3 cameraPosition,
                                      ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
                                      Operation<Void> original) {
        if (!ConcurrentExtraction.deferBlockEntity(renderer, blockEntity, state, partialTicks, cameraPosition,
                breakProgress)) {
            original.call(renderer, blockEntity, state, partialTicks, cameraPosition, breakProgress);
        }
    }
}
