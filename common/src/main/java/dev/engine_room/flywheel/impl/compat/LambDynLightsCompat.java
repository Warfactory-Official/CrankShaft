package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.backend.engine.DynamicLights;
import dev.engine_room.flywheel.impl.mixin.LambDynLightsAccessor;
import dev.lambdaurora.lambdynlights.LambDynLights;
import dev.lambdaurora.lambdynlights.api.behavior.BeaconLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior;
import dev.lambdaurora.lambdynlights.echo.TheEndGatewayBeamLightBehavior;
import dev.lambdaurora.lambdynlights.engine.source.DeferredDynamicLightSource;
import dev.lambdaurora.lambdynlights.engine.source.DynamicLightSource;
import dev.lambdaurora.lambdynlights.engine.source.EntityDynamicLightSource;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;

/**
 * LambDynamicLights reaches instances only through CPU light queries (never the light LUT) and re-meshes terrain;
 * its entity and particle sources and its beams feed {@link DynamicLights}.
 */
public final class LambDynLightsCompat {
    private LambDynLightsCompat() {
    }

    public static void init() {
        if (CompatMod.LAMBDYNLIGHTS.isLoaded) {
            DynamicLights.register(Internals::collect);
        }
    }

    private static final class Internals {
        static void collect(DynamicLights.Sink sink) {
            LambDynLights lamb = LambDynLights.get();
            if (!lamb.config.getDynamicLightsMode().isEnabled()) {
                return;
            }
            for (DynamicLightSource source : ((LambDynLightsAccessor) (Object) lamb).flywheel$dynamicLightSources()) {
                if (source instanceof EntityDynamicLightSource entity && entity.isDynamicLightEnabled()) {
                    int luminance = entity.getLuminance();
                    if (luminance > 0) {
                        sink.accept(entity.getDynamicLightX(), entity.getDynamicLightY(), entity.getDynamicLightZ(),
                                luminance);
                    }
                } else if (source instanceof DeferredDynamicLightSource deferred && !deferred.behavior().isRemoved()) {
                    behavior(deferred.behavior(), sink);
                }
            }
        }

        // lightAtPos (queried at block centres) as segments.
        private static void behavior(DynamicLightBehavior behavior, DynamicLights.Sink sink) {
            switch (behavior) {
                case LineLightBehavior line -> {
                    Vector3dc a = line.getStartPoint();
                    Vector3dc b = line.getEndPoint();
                    sink.segment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), line.getLuminance());
                }
                case BeaconLightBehavior beacon -> sink.segment(beacon.x() + 0.5,
                        beacon.y().orElse(beacon.level().getMinY()) + 0.5, beacon.z() + 0.5, beacon.x() + 0.5,
                        beacon.level().getMaxY() + 0.5, beacon.z() + 0.5, beacon.luminance());
                case TheEndGatewayBeamLightBehavior gateway -> {
                    BlockPos pos = gateway.gateway().getBlockPos();
                    sink.segment(pos.getX() + 0.5, gateway.level().getMinY() + 0.5, pos.getZ() + 0.5,
                            pos.getX() + 0.5, gateway.level().getMaxY() + 0.5, pos.getZ() + 0.5,
                            gateway.gateway().getBlockState().getLightEmission());
                }
                // TODO: other DynamicLightBehavior implementations expose only lightAtPos: no GPU shape.
                default -> {
                }
            }
        }
    }
}
