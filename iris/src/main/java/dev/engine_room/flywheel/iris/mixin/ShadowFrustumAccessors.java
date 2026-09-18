package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

public final class ShadowFrustumAccessors {
    private ShadowFrustumAccessors() {
    }

    @Mixin(AdvancedShadowCullingFrustum.class)
    public interface Advanced {
        @Accessor("planes")
        float[][] flywheel$planes();

        @Accessor("planeCount")
        int flywheel$planeCount();

        @Accessor("boxCuller")
        @Nullable BoxCuller flywheel$boxCuller();
    }

    @Mixin(SafeZoneCullingFrustum.class)
    public interface SafeZone {
        @Accessor("distanceCuller")
        @Nullable BoxCuller flywheel$distanceCuller();
    }

    @Mixin(BoxCullingFrustum.class)
    public interface Box {
        @Accessor("boxCuller")
        BoxCuller flywheel$boxCuller();
    }

    @Mixin(BoxCuller.class)
    public interface Culler {
        @Accessor("maxDistance")
        double flywheel$maxDistance();
    }
}
