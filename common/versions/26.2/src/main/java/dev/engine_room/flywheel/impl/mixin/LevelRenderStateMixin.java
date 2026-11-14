package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.extension.LevelRenderStateExtension;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelRenderState.class)
abstract class LevelRenderStateMixin implements LevelRenderStateExtension {
    @Unique
    private float flywheel$worldPartialTicks;

    @Override
    public float flywheel$worldPartialTicks() {
        return flywheel$worldPartialTicks;
    }

    @Override
    public void flywheel$worldPartialTicks(float partialTick) {
        flywheel$worldPartialTicks = partialTick;
    }
}
