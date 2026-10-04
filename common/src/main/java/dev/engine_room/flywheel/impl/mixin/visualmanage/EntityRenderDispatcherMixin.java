package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.impl.visualization.PrimarySkipState;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    // Compat with EMF: avatar renderers are created outside EntityRenderers' entity-type provider map.
    @SuppressWarnings("rawtypes")
    @WrapOperation(method = "onResourceManagerReload", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createAvatarRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"))
    private Map flywheel$recordEmfAvatarRoots(EntityRendererProvider.Context context, Operation<Map> original) {
        EntityFeatureCompat.beginEmfRootCapture();
        Map renderers;
        boolean restyled;
        try {
            renderers = original.call(context);
        } finally {
            restyled = EntityFeatureCompat.endEmfRootCapture();
        }
        EntityFeatureCompat.emfRestyledAvatars(restyled);
        return renderers;
    }

    @ModifyReturnValue(method = "extractEntity", at = @At("RETURN"))
    private EntityRenderState flywheel$markSkipPrimary(EntityRenderState state, Entity entity) {
        ((PrimarySkipState) state).flywheel$setSkipPrimary(VisualizationManager.supportsVisualization(entity.level())
                && VisualizationHelper.skipVanillaPrimary(entity));
        return state;
    }
}
