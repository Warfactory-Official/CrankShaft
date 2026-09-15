package dev.engine_room.flywheel.backend.compile;

import org.jspecify.annotations.Nullable;

/**
 * {@link #ADDITIVE}: frames with {@code ORDER_INDEPENDENT_ADDITIVE} nodes (alpha 0, occlude nothing). Colour in one
 * write; nearest occluding node depth into a float target ({@code -1} = none), written to depth by a fullscreen pass.
 */
public enum MlabResolveVariant {
    PLAIN(null, ""),
    ADDITIVE("_FLW_MLAB_ADDITIVE", "_additive");

    public final @Nullable String define;
    public final String suffix;

    MlabResolveVariant(@Nullable String define, String suffix) {
        this.define = define;
        this.suffix = suffix;
    }

    public boolean writesDepth() {
        return this == PLAIN;
    }
}
