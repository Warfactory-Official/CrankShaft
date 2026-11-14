package dev.engine_room.flywheel.api.visualization;

import java.util.List;

import dev.engine_room.flywheel.api.visual.FarVisualState;

/**
 * Immutable geometry recipe in block-local coordinates. An empty recipe is a valid
 * representation of a block that contributes no distant geometry.
 */
public record FarVisualModel(List<FarVisualState.Node> children) {
    public static final FarVisualModel EMPTY = new FarVisualModel(List.of());

    public FarVisualModel {
        children = List.copyOf(children);
    }
}
