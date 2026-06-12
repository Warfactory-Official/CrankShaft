package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import dev.engine_room.flywheel.impl.visualization.EntityOutlineSubmits;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla render-state extraction for visualized entities; the block-entity counterpart is split per
 * platform because NeoForge's patched {@code Frustum} arg makes a common descriptor impossible.
 */
@Mixin(value = LevelExtractor.class, priority = 1001)
abstract class LevelExtractorMixin {
    @Shadow
    private ClientLevel level;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), require = 1)
    private void flw$clearOutlineSubmits(CallbackInfo ci) {
        EntityOutlineSubmits.clear();
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"), require = 1)
    private boolean flw$skipVisualizedEntity(LevelExtractor self, Entity entity, Frustum frustum, double camX,
                                             double camY, double camZ, Operation<Boolean> original,
                                             @Local(argsOnly = true) Camera camera,
                                             @Local(argsOnly = true) DeltaTracker deltaTracker) {
        if (VisualizationManager.supportsVisualization(entity.level()) && VisualizationHelper.skipVanillaRender(
                entity)) {
            if (minecraft.shouldEntityAppearGlowing(entity)
                    && original.call(self, entity, frustum, camX, camY, camZ)
                    && (entity != camera.entity() || camera.isDetached() || camera.entity() instanceof LivingEntity living && living.isSleeping())) {
                if (entity.tickCount == 0) {
                    entity.xOld = entity.getX();
                    entity.yOld = entity.getY();
                    entity.zOld = entity.getZ();
                }
                float partialTick = deltaTracker.getGameTimeDeltaPartialTick(
                        !minecraft.level.tickRateManager().isEntityFrozen(entity));
                EntityOutlineSubmits.record(levelRenderer.entityRenderDispatcher().extractEntity(entity, partialTick));
            }
            return false;
        }
        return original.call(self, entity, frustum, camX, camY, camZ);
    }

    @Inject(method = "allChanged", at = @At("HEAD"), require = 1)
    private void flw$onAllChanged(CallbackInfo ci) {
        ClientLevel level = this.level;
        if (level == null) {
            return;
        }
        BackendManagerImpl.onReloadLevelRenderer(level);
        FlwImplXplat.INSTANCE.dispatchReloadLevelRendererEvent(level);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("TAIL"), require = 1)
    private void flw$checkUpdate(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        VisualManager<BlockEntity> blockEntities = manager.blockEntities();
        if (oldState != newState) {
            blockEntities.queueRemove(blockEntity);
            blockEntities.queueAdd(blockEntity);
        } else {
            blockEntities.queueUpdate(blockEntity);
        }
    }
}
