package dev.engine_room.flywheel.backend.glsl;

public enum GlslProfile {
    CORE(""),
    // Compat profile keeps fixed-function builtins (gl_ModelViewProjectionMatrix,
    // gl_TextureMatrix[N], etc.) and legacy attribute streams, needed when sharing matrix
    // state with vanilla MC's GL_MODELVIEW stack on 1.12.2 (see ChunkOitPrograms).
    COMPATIBILITY("compatibility"),
    ;

    public final String token;

    GlslProfile(String token) {
        this.token = token;
    }
}
