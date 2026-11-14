package dev.engine_room.flywheel.iris.compile;

/**
 * The opaque DH program owns the retained-surface variant and its GPU pixel list.
 */
public interface FarDhOwner {
    void flywheel$far(FarDhProgram program);

    FarDhProgram flywheel$far();

    void flywheel$staticFar(FarStaticDhProgram program);

    FarStaticDhProgram flywheel$staticFar();

    void flywheel$layeredFar(FarLayerDhProgram program);

    FarLayerDhProgram flywheel$layeredFar();

    void flywheel$hostOit(FarDhOit graph);

    FarDhOit flywheel$hostOit();
}
