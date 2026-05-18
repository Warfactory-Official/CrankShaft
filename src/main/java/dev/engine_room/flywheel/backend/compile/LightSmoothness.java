package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;

import java.util.Locale;

public enum LightSmoothness {
    FLAT(0, false),
    TRI_LINEAR(1, false),
    SMOOTH(2, false),
    SMOOTH_INNER_FACE_CORRECTED(2, true),
    ;

    private final int smoothnessDefine;
    private final boolean innerFaceCorrection;

    LightSmoothness(int smoothnessDefine, boolean innerFaceCorrection) {
        this.smoothnessDefine = smoothnessDefine;
        this.innerFaceCorrection = innerFaceCorrection;
    }

    public void onCompile(Compilation comp) {
        comp.define("_FLW_LIGHT_SMOOTHNESS", Integer.toString(smoothnessDefine));
        if (innerFaceCorrection) {
            comp.define("_FLW_INNER_FACE_CORRECTION");
        }
    }

    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
