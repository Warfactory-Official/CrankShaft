package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.impl.visualization.HeldItemHosts;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmedEntityRenderState.class)
abstract class ArmedEntityRenderStateMixin {
    @Inject(method = "extractArmedEntityRenderState", at = @At("TAIL"), require = 1)
    private static void flw$hostVisualizedItems(LivingEntity entity, ArmedEntityRenderState state,
                                                ItemModelResolver itemModelResolver, float partialTicks,
                                                CallbackInfo ci) {
        HeldItemHosts.extract(entity, state);
    }
}
