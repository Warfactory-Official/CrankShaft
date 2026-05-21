package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.engine.SectionPos;
import dev.engine_room.flywheel.impl.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.compat.DynamicLightProvider;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

// need to be late to fire after alfheim's @Overwrite
@Mixin(value = RenderGlobal.class, priority = 1500)
public abstract class MixinRenderGlobal {

    @Shadow
    private WorldClient world;
    @Shadow
    @Final
    private Map<Integer, DestroyBlockProgress> damagedBlocks;

    @Inject(method = "notifyLightSet", at = @At("TAIL"), require = 1)
    private void flw$notifyLightUpdate(BlockPos pos, CallbackInfo ci) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(world);
        if (manager == null) {
            return;
        }
        long sectionPos = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        manager.onLightUpdate(sectionPos, EnumSkyBlock.BLOCK);
        manager.onLightUpdate(sectionPos, EnumSkyBlock.SKY);
    }

    @Inject(method = "onEntityAdded", at = @At("TAIL"), require = 1)
    private void flw$entityAdded(Entity entityIn, CallbackInfo ci) {
        VisualizationManager manager = VisualizationManager.get(world);
        if (manager != null) {
            manager.entities().queueAdd(entityIn);
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("TAIL"), require = 1)
    private void flw$entityRemoved(Entity entityIn, CallbackInfo ci) {
        VisualizationManager manager = VisualizationManager.get(world);
        if (manager != null) {
            manager.entities().queueRemove(entityIn);
        }
    }

    @Inject(method = "notifyBlockUpdate", at = @At("TAIL"), require = 1)
    private void flw$blockEntityUpdate(World worldIn, BlockPos pos, IBlockState oldState, IBlockState newState, int flags, CallbackInfo ci) {
        if (worldIn != world) {
            return;
        }
        VisualizationManager manager = VisualizationManager.get(world);
        if (manager == null) {
            return;
        }
        TileEntity tileEntity = world.getTileEntity(pos);
        if (tileEntity == null) {
            return;
        }
        if (oldState != newState) {
            manager.blockEntities().queueRemove(tileEntity);
            VisualizationHelper.tryAddBlockEntity(tileEntity);
        } else {
            VisualizationHelper.queueUpdate(tileEntity);
        }
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"), require = 1)
    private void flw$onStartLevelRender(Entity viewEntity, double partialTicks, ICamera camera,
                                                 int frameCount, boolean playerSpectator,
                                                 CallbackInfo ci) {
        VisualizationManagerImpl impl = VisualizationManagerImpl.get(world);
        if (impl == null) {
            return;
        }
        if (DynamicLightProvider.ANY_LOADED) {
            DynamicLightProvider provider = DynamicLightProvider.INSTANCE;
            provider.captureSnapshot();
            provider.notifyAffected(impl::isAnyLightUpdatedSection, impl);
        }
        impl.renderDispatcher().onStartLevelRender(
                RenderContextImpl.create((RenderGlobal) (Object) this, world, viewEntity, (float) partialTicks));
    }

    @Inject(method = "renderEntities", at = @At("HEAD"), require = 1)
    private void flw$cacheSupports(Entity renderViewEntity, ICamera camera, float partialTicks,
                                            CallbackInfo ci) {
        VisualizationHelper.cacheSupportsVisualization(world);
    }

    @Inject(method = "renderEntities", at = @At("TAIL"), require = 1)
    private void flw$afterEntities(Entity renderViewEntity, ICamera camera, float partialTicks,
                                            CallbackInfo ci) {
        VisualizationHelper.dispatchAfterEntities(world);
    }

    // TAIL of the public 4-arg overload so we cover both EntityRenderer's direct call and any
    // forge mod that hits the same entry point.
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("TAIL"), require = 1)
    private void flw$afterTranslucent(BlockRenderLayer layer, double partialTicks, int pass, Entity entity,
                                      CallbackInfoReturnable<Integer> cir) {
        if (layer != BlockRenderLayer.TRANSLUCENT) {
            return;
        }
        VisualizationHelper.dispatchAfterTranslucent(world);
    }

    // Suppress vanilla's translucent chunk draw when chunk-OIT replay will re-issue the same VBOs
    // through the OIT pipeline (renderOit, fired by the TAIL inject above).
    @Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;)V"),
            require = 1)
    private void flw$maybeSkipTranslucentDraw(RenderGlobal self, BlockRenderLayer layer) {
        // Suppress vanilla TRANSLUCENT when chunk-OIT will actually replay it.
        // ChunkTranslucentOit.replay redraws these chunks through the OIT pipeline when
        // renderOit fires at the TAIL inject above.
        if (layer == BlockRenderLayer.TRANSLUCENT && FlwPrograms.chunkOitPrograms() != null
                && VisualizationManager.supportsVisualization(world)) {
            return;
        }
        // AT-widened to public so we can call the original draw helper directly.
        self.renderBlockLayer(layer);
    }

    @Inject(method = "drawBlockDamageTexture", at = @At("HEAD"), require = 1)
    private void flw$beforeCrumbling(Tessellator tessellatorIn,
                                              BufferBuilder bufferBuilderIn,
                                              Entity entityIn, float partialTicks, CallbackInfo ci) {
        VisualizationHelper.dispatchBeforeCrumbling(world, damagedBlocks);
    }
}
