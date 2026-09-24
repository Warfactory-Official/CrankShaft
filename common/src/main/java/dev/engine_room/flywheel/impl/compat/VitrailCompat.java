package dev.engine_room.flywheel.impl.compat;

import dev.vitrail.render.PackChain;

/**
 * Vitrail draws a shader pack on Vulkan through its own programs, which never see instanced geometry: the engine
 * suspends while one draws, and vanilla renderers go through the pack.
 */
public final class VitrailCompat {
    private VitrailCompat() {
    }

    public static boolean drawingPack() {
        return CompatMod.VITRAIL.isLoaded && Internals.drawingPack();
    }

    private static final class Internals {
        static boolean drawingPack() {
            return PackChain.drawingPack();
        }
    }
}
