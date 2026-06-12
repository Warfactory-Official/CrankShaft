package dev.engine_room.flywheel.backend.glsl;

public enum GlslProfile {
    CORE("");

    public final String token;

    GlslProfile(String token) {
        this.token = token;
    }
}
