package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import org.jspecify.annotations.Nullable;

/**
 * Extra native main-view producers registered by the host integration. Register at client setup and unregister
 * before its owner is destroyed. Every callback runs on the render thread; begin/draw borrow the current chain's
 * targets and must neither clear nor resolve them. Shaderpack graphs use their own producer seam.
 */
public final class MainOitHooks {
    private static @Nullable Provider provider;

    private MainOitHooks() {
    }

    public static void register(@Nullable Provider current) {
        provider = current;
    }

    /**
     * Presence is independent of publication, so callers may request a cold producer group.
     */
    public static boolean has() {
        return provider != null && provider.has();
    }

    static @Nullable Provider select(@Nullable OitInsertMode mode) {
        var current = provider;
        return current != null && current.has() && current.ready(mode) ? current : null;
    }

    public interface Provider {
        boolean has();

        /**
         * Preflights the complete producer group without consuming retained work or claiming native draws.
         */
        boolean ready(@Nullable OitInsertMode mode);

        /**
         * Actual main/Fabulous attachments; return the explicit projection/depth conventions for this view.
         */
        RawOitTarget begin(GpuTextureView color, GpuTextureView depth, int width, int height);

        /**
         * The chain's current depth-range/coefficient/evaluation or insert pass, sharing its eventual resolve.
         */
        void draw(RawOitPass pass);
    }
}
