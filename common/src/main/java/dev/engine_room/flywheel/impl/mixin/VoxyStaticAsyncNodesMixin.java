package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native terrain and captures hold independent references to a shared top-level section.
 */
@Mixin(AsyncNodeManager.class)
abstract class VoxyStaticAsyncNodesMixin implements VoxyStaticVisuals.AsyncAccess {
    @Shadow
    @Final
    private NodeManager manager;

    @Shadow
    @Final
    private BasicAsyncGeometryManager geometryManager;

    @Unique
    private VoxyStaticSections flywheel$staticSections;
    @Unique
    private final LongOpenHashSet flywheel$terrainRoots = new LongOpenHashSet();
    @Unique
    private final LongOpenHashSet flywheel$captureRoots = new LongOpenHashSet();
    @Unique
    private boolean flywheel$changingCaptureRoot;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$createStaticSections(int maxNodes, IGeometryData geometry,
                                               RenderGenerationService generation, CallbackInfo _) {
        var world = generation.flywheel$world();
        flywheel$staticSections = new VoxyStaticSections((AsyncNodeManager) (Object) this, world);
        ((VoxyStaticVisuals.GeometryAccess) geometry).flywheel$staticSections(flywheel$staticSections);
        manager.flywheel$staticSections(flywheel$staticSections);
        geometryManager.flywheel$staticSections(flywheel$staticSections);
    }

    @Override
    public VoxyStaticSections flywheel$staticSections() {
        return flywheel$staticSections;
    }

    @WrapMethod(method = "addTopLevel")
    private void flywheel$addTerrainRoot(long position, Operation<Void> original) {
        if (flywheel$changingCaptureRoot) {
            original.call(position);
        } else if (flywheel$terrainRoots.add(position) && !flywheel$captureRoots.contains(position)) {
            original.call(position);
        }
    }

    @WrapMethod(method = "removeTopLevel")
    private void flywheel$removeTerrainRoot(long position, Operation<Void> original) {
        if (flywheel$changingCaptureRoot) {
            original.call(position);
        } else if (flywheel$terrainRoots.remove(position) && !flywheel$captureRoots.contains(position)) {
            original.call(position);
        }
    }

    @Override
    public void flywheel$staticRoot(long position, boolean add) {
        if (add) flywheel$captureRoots.add(position);
        else flywheel$captureRoots.remove(position);
        if (flywheel$terrainRoots.contains(position)) return;
        flywheel$changingCaptureRoot = true;
        try {
            var self = (AsyncNodeManager) (Object) this;
            if (add) self.addTopLevel(position);
            else self.removeTopLevel(position);
        } finally {
            flywheel$changingCaptureRoot = false;
        }
    }
}

#endif
