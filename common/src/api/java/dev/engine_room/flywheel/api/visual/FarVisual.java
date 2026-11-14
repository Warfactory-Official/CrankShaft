package dev.engine_room.flywheel.api.visual;

/**
 * A block entity visual that declares its distant state to a supported level-of-detail renderer (Voxy, Distant Horizons)
 * when visualization drops it: its chunk is no longer held by the client, or visualization resets.
 *
 * <p>The retained state is drawn in the LoD renderer's pass until the block entity is visualized again, its loaded
 * chunk no longer holds one, or it leaves the LoD reach. Breaks and block state changes in a loaded chunk discard the
 * visual without a capture.
 *
 * <p>Any visualized block entity, far visual or not, also leaves its block model behind when that model reaches past
 * its block: LoD renderers bake one block per block state and lose the rest.
 */
public interface FarVisual extends Visual {
    /**
     * Describes the state this visual retains at a distance. Called once, immediately before {@link #delete()} and
     * on its thread. Static geometry may use a different pose from the live visual, such as a closed container.
     * The implementation consumes the declaration before deletion; the visual does not perform capture itself.
     */
    FarVisualState retainedState();
}
