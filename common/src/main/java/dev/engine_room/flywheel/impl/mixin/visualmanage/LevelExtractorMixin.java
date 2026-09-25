package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import dev.engine_room.flywheel.impl.compat.EntityCullingCompat;
import dev.engine_room.flywheel.impl.visualization.ConcurrentExtraction;
import dev.engine_room.flywheel.impl.visualization.EntityOutlineSubmits;
import dev.engine_room.flywheel.impl.visualization.HeldItemHosts;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
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
 * Skips vanilla render-state extraction for visualized entities and brackets the entity and block entity loops for
 * {@link ConcurrentExtraction}.
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

    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"), require = 1)
    private void flw$beginConcurrentExtraction(CallbackInfo ci) {
        ConcurrentExtraction.begin();
    }

    // Not the loops' RETURNs: Sodium and others cancel or replace the block entity loop.
    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractBlockOutline(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"), require = 1)
    private void flw$endConcurrentExtraction(CallbackInfo ci) {
        ConcurrentExtraction.end(levelRenderer.entityRenderDispatcher());
    }

    // Inside extractEntity: hooks on it still run here (e.g. a HEAD cull returning its own state).
    @WrapOperation(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"), require = 1)
    private EntityRenderState flw$deferEntity(EntityRenderDispatcher dispatcher, Entity entity, float partialTick,
                                              Operation<EntityRenderState> original) {
        if (ConcurrentExtraction.deferEntity(dispatcher, entity, partialTick)) {
            return ConcurrentExtraction.PENDING;
        }
        EntityRenderState state = original.call(dispatcher, entity, partialTick);
        HeldItemHosts.extract(entity, state);
        return state;
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"), require = 1)
    private EntityRenderState flw$slotDeferredEntity(LevelExtractor self, Entity entity, float partialTick,
                                                     Operation<EntityRenderState> original,
                                                     @Local(argsOnly = true) LevelRenderState output) {
        EntityRenderState state = original.call(self, entity, partialTick);
        if (state == ConcurrentExtraction.PENDING) {
            ConcurrentExtraction.slotEntity(output.entityRenderStates);
        }
        return state;
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"), require = 1)
    private boolean flw$skipVisualizedEntity(LevelExtractor self, Entity entity, Frustum frustum, double camX,
                                             double camY, double camZ, Operation<Boolean> original,
                                             @Local(argsOnly = true) Camera camera,
                                             @Local(argsOnly = true) DeltaTracker deltaTracker) {
        if (VisualizationManager.supportsVisualization(entity.level()) && VisualizationHelper.skipVanillaRender(
                entity)) {
            boolean glowing = minecraft.shouldEntityAppearGlowing(entity);
            boolean visible = (glowing || EntityCullingCompat.ACTIVE) && original.call(self, entity, frustum, camX, camY, camZ);
            if (visible) {
                EntityCullingCompat.markVisible(entity);
            }
            if (glowing && visible
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
        // Port: 26.2 bakes the level's cardinal shading into block meshes; Iris toggles it per pack, then reloads here.
        Models.invalidate();
        RendererReloadCache.onReloadLevelRenderer();
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
