package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.Iterables;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Iris's shadow pass extracts entities itself, past the level extractor's visualized-entity skip; their guest draws
 * already cast the shadow.
 */
@Mixin(ShadowRenderer.class)
abstract class ShadowEntitiesMixin {
    @ModifyExpressionValue(method = "extractVisibleEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> flywheel$skipVisualized(Iterable<Entity> entities) {
        if (!VisualizationManager.supportsVisualization(Minecraft.getInstance().level)) {
            return entities;
        }
        return Iterables.filter(entities, entity -> !VisualizationHelper.skipVanillaRender(entity));
    }
}
