package dev.engine_room.flywheel.impl.compat;

/**
 * Captured Voxy stages after its own preprocessing and replacements; owned with the original native program.
 */
public interface NativeWaterSource {
    void flywheel$waterSources(String vertex, String fragment);

    NativeWaterShaders flywheel$waterShaders();
}
