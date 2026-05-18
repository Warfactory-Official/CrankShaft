package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Chunk.onUnload routes TEs through markTileEntityForRemoval; invalidate() is never called,
// so our TileEntity.invalidate hook misses chunk-unloads. Hook updateEntities removeAll instead
// to avoid flipping tileEntityInvalid on TEs other mods may still observe.
@Mixin(World.class)
public abstract class MixinWorld {

    @Shadow
    @Final
    private List<TileEntity> tileEntitiesToBeRemoved;

    @Inject(method = "addTileEntity(Lnet/minecraft/tileentity/TileEntity;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;onLoad()V", shift = At.Shift.AFTER, remap = false), require = 1)
    private void flw$addVisualAfterOnLoad(TileEntity tile, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (!(self instanceof WorldClient)) {
            return;
        }
        VisualizationHelper.tryAddBlockEntity(tile);
    }

    @Inject(method = "updateEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;removeAll(Ljava/util/Collection;)Z", ordinal = 1, shift = At.Shift.AFTER), require = 1)
    private void flw$removeUnloadedTeVisuals(CallbackInfo ci) {
        World self = (World) (Object) this;
        if (!(self instanceof WorldClient)) return;
        VisualizationManager manager = VisualizationManager.get(self);
        if (manager != null) {
            for (TileEntity te : tileEntitiesToBeRemoved) {
                manager.blockEntities().queueRemove(te);
            }
        }
    }
}
