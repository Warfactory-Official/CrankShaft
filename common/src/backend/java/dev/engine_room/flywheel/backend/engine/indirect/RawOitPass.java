package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import org.jspecify.annotations.Nullable;

/**
 * A borrowed producer window. Exactly one mode is non-null. Call {@link #bind()} after host setup and before
 * drawing: it restores this pass's targets, depth/blend state, read textures and insert storage. Render thread.
 */
public record RawOitPass(RawOitTarget target, @Nullable OitMode wavelet, @Nullable OitInsertMode insert,
                         int opaqueDepth, Runnable bindings) {
    public void bind() {
        bindings.run();
    }
}
