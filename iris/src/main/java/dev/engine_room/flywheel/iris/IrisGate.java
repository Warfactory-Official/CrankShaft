package dev.engine_room.flywheel.iris;

import net.irisshaders.iris.Iris;

import java.io.IOException;
import java.io.UncheckedIOException;

final class IrisGate {
    private IrisGate() {
    }

    static boolean isPackInUse() {
        return Iris.isPackInUseQuick();
    }

    static double renderScale() {
        return Iris.isPackInUseQuick() ? Iris.getCurrentPack().orElseThrow().flywheel$renderScale() : 1;
    }

    static boolean supportsShaderpackOit() {
        return Iris.getCurrentPack().map(pack ->
                pack.flywheel$forwardProfile() != null || pack.flywheel$deferredOit() != null).orElse(false);
    }

    static void reload() {
        try {
            Iris.reload();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
