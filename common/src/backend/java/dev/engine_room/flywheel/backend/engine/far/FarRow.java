package dev.engine_room.flywheel.backend.engine.far;

import org.joml.Vector3fc;

/**
 * One captured instance and its pose: {@code radiansPerTick == 0} keeps it as captured, otherwise it turns about
 * {@code axis} (unit) through {@code pivot} (capture-origin relative) from {@code startTick}, {@code phase} radians
 * past its rest pose.
 */
public record FarRow(FarInstance snapshot, Vector3fc pivot, Vector3fc axis, float radiansPerTick,
                     int startTick, float phase) {
}
