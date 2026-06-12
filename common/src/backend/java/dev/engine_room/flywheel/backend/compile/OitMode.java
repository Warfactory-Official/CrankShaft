package dev.engine_room.flywheel.backend.compile;

public enum OitMode {
    OFF("", ""),
    DEPTH_RANGE("_FLW_DEPTH_RANGE", "_depth_range"),
    GENERATE_COEFFICIENTS("_FLW_COLLECT_COEFFS", "_generate_coefficients"),
    EVALUATE("_FLW_EVALUATE", "_resolve"),
    ;

    public final String define;
    public final String name;

    OitMode(String define, String name) {
        this.define = define;
        this.name = name;
    }
}
