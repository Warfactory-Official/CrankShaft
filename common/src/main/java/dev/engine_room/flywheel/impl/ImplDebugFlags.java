package dev.engine_room.flywheel.impl;

public class ImplDebugFlags {
    public static boolean PAUSE_UPDATES = false;

    #if MC < 26.3
    /**
     * Alpha-blit the RAW redirected layer color over the frame after the OIT chain.
     */
    public static boolean FABULOUS_LAYER_VIEW = false;
    #endif
}
