package dev.engine_room.flywheel.iris;

/**
 * Detects the Iris API before mixins or renderer classes link it.
 */
public final class IrisRuntime {
    public static final boolean EXPERIMENTAL = IrisRuntime.class.getClassLoader()
                                                                .getResource(
                                                                        "net/irisshaders/iris/backend/RenderBackend.class") != null;

    private IrisRuntime() {
    }
}
