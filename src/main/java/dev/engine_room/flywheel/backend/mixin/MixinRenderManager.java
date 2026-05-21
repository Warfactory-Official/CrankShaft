package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.extension.EntityExtension;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderManager.class)
public abstract class MixinRenderManager {

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void flw$skipVisualizedEntity(Entity entity, double x, double y, double z,
                                                   float yaw, float partialTicks, boolean noTransforms,
                                                   CallbackInfo ci) {
        if (VisualizationManager.supportsVisualization(entity.world)
                && ((EntityExtension) entity).flw$skipVanillaRender()) {
            ci.cancel();
        }
    }
}
