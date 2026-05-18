package dev.engine_room.flywheel.backend.engine.uniform;

import java.util.Locale;

public enum DebugMode {
    OFF,
    NORMALS,
    INSTANCE_ID,
    LIGHT_LEVEL,
    LIGHT_COLOR,
    OVERLAY,
    DIFFUSE,
    MODEL_ID,
    ;

    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
