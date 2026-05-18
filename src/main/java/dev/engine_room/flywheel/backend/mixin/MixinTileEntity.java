package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.extension.TileEntityExtension;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntity.class)
public abstract class MixinTileEntity implements TileEntityExtension {

    @Shadow
    protected World world;

    @Inject(method = "invalidate", at = @At("HEAD"), require = 1)
    private void flw$removeVisualOnInvalidate(CallbackInfo ci) {
        if (!(world instanceof WorldClient)) {
            return;
        }
        VisualizationManager manager = VisualizationManager.get(world);
        if (manager != null) {
            manager.blockEntities().queueRemove((TileEntity) (Object) this);
        }
    }
}
