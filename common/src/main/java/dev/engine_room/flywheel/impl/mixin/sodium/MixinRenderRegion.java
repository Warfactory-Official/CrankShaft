package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderRegion.class)
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

    // Compat with Sodium 0.9.2: shared arenas relocate a region's segments (defragmentation) and move it between
    // buffers outside its own uploads; the mirrored section records carry the stale offsets until re-fed.
    @Inject(method = "onGeometryBufferChange()V", at = @At("TAIL"), require = 1)
    private void flywheel$onGeometryBufferChange(CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener != null) {
            listener.markRegion((RenderRegion) (Object) this);
        }
    }

    @Inject(method = "onIndexBufferChange()V", at = @At("TAIL"), require = 1)
    private void flywheel$onIndexBufferChange(CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener != null) {
            listener.markRegion((RenderRegion) (Object) this);
        }
    }

    @Inject(method = "onGeometrySegmentChange(I)V", at = @At("TAIL"), require = 1)
    private void flywheel$onGeometrySegmentChange(int ownerIndex, CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener != null) {
            listener.markSection((RenderRegion) (Object) this, RenderRegion.unpackSectionIndex(ownerIndex));
        }
    }

    @Inject(method = "onIndexSegmentChange(I)V", at = @At("TAIL"), require = 1)
    private void flywheel$onIndexSegmentChange(int ownerIndex, CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener == null) {
            return;
        }
        if (ownerIndex == RenderRegion.SHARED_INDEX_DATA_INDEX) {
            listener.markRegion((RenderRegion) (Object) this);
        } else {
            listener.markSection((RenderRegion) (Object) this, RenderRegion.unpackSectionIndex(ownerIndex));
        }
    }
}
