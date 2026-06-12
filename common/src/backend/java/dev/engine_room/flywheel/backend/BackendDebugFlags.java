package dev.engine_room.flywheel.backend;

public class BackendDebugFlags {
    public static boolean LIGHT_STORAGE_VIEW = false;
    // When true, renderOit early-outs -- a benchmark toggle to isolate the fixed pass cost.
    // Flywheel translucent instances are NOT drawn while skipped. /flywheel debug oit on|off.
    public static boolean SKIP_OIT = false;
}
