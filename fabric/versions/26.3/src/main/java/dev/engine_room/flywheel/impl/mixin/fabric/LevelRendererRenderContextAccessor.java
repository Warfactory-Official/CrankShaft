package dev.engine_room.flywheel.impl.mixin.fabric;

import net.fabricmc.fabric.impl.client.rendering.level.LevelRenderContextImpl;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Compat with Fabric rendering: read its prepared context after the owning mixin installs the field.
@Mixin(value = LevelRenderer.class, priority = 900)
public interface LevelRendererRenderContextAccessor {
    @Accessor("renderContext")
    LevelRenderContextImpl flywheel$renderContext();
}
