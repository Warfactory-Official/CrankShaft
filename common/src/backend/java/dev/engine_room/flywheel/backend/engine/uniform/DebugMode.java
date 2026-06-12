package dev.engine_room.flywheel.backend.engine.uniform;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum DebugMode implements StringRepresentable {
    OFF,
    NORMALS,
    INSTANCE_ID,
    LIGHT_LEVEL,
    LIGHT_COLOR,
    OVERLAY,
    DIFFUSE,
    MODEL_ID,
    ;

    public static final StringRepresentable.EnumCodec<DebugMode> CODEC = StringRepresentable.fromEnum(
            DebugMode::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
