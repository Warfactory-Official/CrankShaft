package dev.engine_room.flywheel.impl.visual;

import dev.engine_room.flywheel.api.visual.DistanceUpdateLimiter;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import net.minecraft.client.Camera;
import org.joml.FrustumIntersection;

public record DynamicVisualContextImpl(Camera camera, FrustumIntersection frustum, float partialTick,
                                       DistanceUpdateLimiter limiter) implements DynamicVisual.Context {
}
