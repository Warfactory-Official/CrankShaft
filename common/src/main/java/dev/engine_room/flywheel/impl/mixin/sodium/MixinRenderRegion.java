package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinRenderRegion {
    @Inject(method = "removeSection(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)V",
            at = @At("HEAD"), require = 1)
    private void flywheel$onSectionRemoved(RenderSection section, CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener == null) {
            return;
        }
        listener.onSectionRemoved(((RenderRegion) (Object) this).getId(), section.getSectionIndex());
    }
}
