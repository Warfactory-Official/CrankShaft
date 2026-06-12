package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

public enum LightSmoothness implements StringRepresentable {
    FLAT(0, false),
    TRI_LINEAR(1, false),
    SMOOTH(2, false),
    SMOOTH_INNER_FACE_CORRECTED(2, true),
    ;

    public static final StringRepresentable.EnumCodec<LightSmoothness> CODEC = StringRepresentable.fromEnum(
            LightSmoothness::values);

    private final int smoothnessDefine;
    private final boolean innerFaceCorrection;

    LightSmoothness(int smoothnessDefine, boolean innerFaceCorrection) {
        this.smoothnessDefine = smoothnessDefine;
        this.innerFaceCorrection = innerFaceCorrection;
    }

    public void appendDefines(Compilation ctx) {
        ctx.define("_FLW_LIGHT_SMOOTHNESS", String.valueOf(smoothnessDefine));
        if (innerFaceCorrection) {
            ctx.define("_FLW_INNER_FACE_CORRECTION");
        }
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
