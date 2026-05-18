package dev.engine_room.flywheel.backend.mixin.mod.sodium;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Sodium forks (Neonium, Vintagium, Relictium) cancel vanilla RenderGlobal.renderEntities,
// so flywheel's TAIL hook there never fires.
@Mixin(SodiumWorldRenderer.class)
public abstract class MixinSodiumWorldRenderer {

    @Shadow(remap = false)
    @Dynamic
    private void renderTE(TileEntity tileEntity, int pass, float partialTicks, int damageProgress) {
        throw new AssertionError();
    }

    @Dynamic
    @Inject(method = "renderTileEntities", at = @At("HEAD"), require = 1, remap = false)
    private void flw$cacheSupports(float partialTicks, Map<Integer, DestroyBlockProgress> damagedBlocks,
                                   CallbackInfo ci) {
        VisualizationHelper.cacheSupportsVisualization(Minecraft.getMinecraft().world);
    }

    @Dynamic
    @Inject(method = "renderTileEntities", at = @At("TAIL"), require = 1, remap = false)
    private void flw$afterEntities(float partialTicks, Map<Integer, DestroyBlockProgress> damagedBlocks,
                                   CallbackInfo ci) {
        VisualizationHelper.dispatchAfterEntities(Minecraft.getMinecraft().world);
    }

    @Dynamic
    @Redirect(method = "renderTileEntities",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;preDrawBatch()V", remap = false),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;drawBatch(I)V", remap = false)),
            at = @At(value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/SodiumWorldRenderer;renderTE(Lnet/minecraft/tileentity/TileEntity;IFI)V",
                    remap = false),
            require = 2,
            remap = false)
    private void flw$skipVisualizedRenderTE(SodiumWorldRenderer self, TileEntity tileEntity, int pass,
                                            float partialTicks, int damageProgress) {
        if (VisualizationHelper.shouldSkipTileEntity(tileEntity)) {
            return;
        }
        this.renderTE(tileEntity, pass, partialTicks, damageProgress);
    }

    @Dynamic
    @Redirect(method = "renderTileEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;getTileEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;",
                    ordinal = 0,
                    remap = true),
            require = 1,
            remap = false)
    private TileEntity flw$skipVisualizedDamageTE(WorldClient world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te != null && VisualizationHelper.shouldSkipTileEntity(te)) {
            return null;
        }
        return te;
    }
}
