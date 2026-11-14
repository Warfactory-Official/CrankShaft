package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * Backend-owned instance bytes and the outer coordinate frame resolved before the source visual is deleted.
 */
public record FarInstance(InstanceType<?> type, Model model, int bias, byte[] data, int light,
                          BlockState fallbackState, Matrix4fc pose, Matrix3fc normal, boolean terrain) {
}
