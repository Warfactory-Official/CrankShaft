package dev.engine_room.flywheel.backend.compile;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum ShaderCompilationMode implements StringRepresentable {
    PRECOMPILE,
    ON_DEMAND;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
