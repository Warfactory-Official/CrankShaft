package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.impl.visualization.PrimarySkipState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
abstract class EntityRenderStateMixin implements PrimarySkipState {
    @Unique
    private boolean flywheel$skipPrimary;

    @Override
    public boolean flywheel$skipPrimary() {
        return flywheel$skipPrimary;
    }

    @Override
    public void flywheel$setSkipPrimary(boolean skip) {
        flywheel$skipPrimary = skip;
    }
}
