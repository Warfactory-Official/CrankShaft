package dev.engine_room.flywheel.api.instance;

import dev.engine_room.flywheel.api.backend.BackendImplemented;
import dev.engine_room.flywheel.api.model.Model;

@BackendImplemented
public interface InstancerProvider {
    /**
     * Whether this provider can create GPU simulations.
     */
    default boolean supportsGpuSimulation() {
        return false;
    }

    /**
     * Get a persistent GPU population for an effect identity. Reusing the identity reacquires its existing
     * simulation state, including after a render-origin change. Delete the population when the effect ends.
     *
     * The arguments supply the effect's stable identity, the output instances' layout and shaders, the model to
     * render for each instance, the simulation programs and storage sizes, the maximum number of population slots,
     * and the draw ordering bias, as in {@link #instancer(InstanceType, Model, int)}.
     */
    default GpuPopulation gpuPopulation(Object _, InstanceType<?> _, Model _,
                                        GpuSimulation _, int _, int _) {
        throw new UnsupportedOperationException("This backend does not support GPU simulations");
    }

    /**
     * Get a persistent GPU population with {@code bias == 0}.
     */
    default GpuPopulation gpuPopulation(Object identity, InstanceType<?> type, Model model,
                                        GpuSimulation simulation, int capacity) {
        return gpuPopulation(identity, type, model, simulation, capacity, 0);
    }

    /**
     * Get an instancer for the given instance type rendering the given model.
     *
     * <p>Calling this method twice with the same arguments in the
     * same frame will return the same instancer.</p>
     *
     * <p>It is NOT safe to store instancers between frames. Each
     * time you need an instancer, you should call this method.</p>
     *
     * <h2>Render Order</h2>
     * <p>In general, you can assume all instances in the same instancer will be rendered in a single draw call.
     * Backends are free to optimize the ordering of draw calls to a certain extent, but utilities are provided to let
     * you exert control over the ordering.</p>
     * <h4>Mesh Order</h4>
     * <p>For one, Meshes within a Model are guaranteed to render in the order they appear in their containing list.
     * This lets you e.g. preserve (or break!) vanilla's chunk RenderType order guarantees or control which Meshes of
     * your Model render over others.</p>
     * <h4>Bias Order</h4>
     * <p>The other method is via the {@code bias} parameter to this method. An instancer with a lower bias will have
     * its instances draw BEFORE an instancer with a higher bias. This allows you to control the render order between
     * your instances to e.g. create an "overlay" instance to selectively color or apply decals to another instance.</p>
     *
     * @param type  The instance type to parameterize your instances by.
     * @param model The Model to instance.
     * @param bias  A weight to control render order between instancers.
     *              Instancers are rendered in ascending order by bias.
     * @return An instancer.
     */
    <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias);

    /**
     * Get an instancer with no bias for the given instance type rendering the given model with.
     *
     * @param type  The instance type to parameterize your instances by.
     * @param model The model to instance.
     * @return An instancer with {@code bias == 0}.
     */
    default <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model) {
        return instancer(type, model, 0);
    }
}
