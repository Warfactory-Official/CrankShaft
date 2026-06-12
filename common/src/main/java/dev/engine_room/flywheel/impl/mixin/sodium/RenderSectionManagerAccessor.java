package dev.engine_room.flywheel.impl.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes fields {@link MixinRenderSectionManager} needs for render-list cancellation + coherent stats.
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccessor {
    @Accessor("regions")
    RenderRegionManager flywheel$getRegions();

    @Accessor("renderLists")
    void flywheel$setRenderLists(SortedRenderLists renderLists);

    @Accessor("needsRenderListUpdate")
    void flywheel$setNeedsRenderListUpdate(boolean value);

    @Accessor("cameraChanged")
    void flywheel$setCameraChanged(boolean value);
}
