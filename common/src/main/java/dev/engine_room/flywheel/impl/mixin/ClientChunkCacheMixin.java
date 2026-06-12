package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    ClientLevel level;

    @Inject(method = "onLightUpdate", at = @At("HEAD"), require = 1)
    private void flw$onLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager != null) {
            manager.onLightUpdate(pos, layer);
        }
    }
}
