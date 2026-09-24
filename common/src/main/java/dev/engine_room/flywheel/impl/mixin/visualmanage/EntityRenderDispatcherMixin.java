package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.PrimarySkipState;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @ModifyReturnValue(method = "extractEntity", at = @At("RETURN"))
    private EntityRenderState flywheel$markSkipPrimary(EntityRenderState state, Entity entity) {
        ((PrimarySkipState) state).flywheel$setSkipPrimary(VisualizationManager.supportsVisualization(entity.level())
                && VisualizationHelper.skipVanillaPrimary(entity));
        return state;
    }
}
