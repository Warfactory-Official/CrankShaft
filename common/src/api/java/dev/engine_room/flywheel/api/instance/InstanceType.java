package dev.engine_room.flywheel.api.instance;

import dev.engine_room.flywheel.api.layout.Layout;
import net.minecraft.resources.Identifier;

import java.util.function.LongConsumer;

/**
 * An InstanceType contains metadata for a specific instance that Flywheel can interface with.
 *
 * @param <I> The java representation of the instance.
 */
public interface InstanceType<I extends Instance> {
    /**
     * @param handle A handle that allows you to mark the instance as dirty or deleted.
     * @return A new, zeroed instance of I.
     */
    I create(InstanceHandle handle);

    /**
     * The native memory layout of this instance type.
     *
     * <p>This layout determines what fields are made available to the instance type's shaders
     * as well as determining how the fields are arranged in memory.
     *
     * @return The layout of this instance type.
     */
    Layout layout();

    /**
     * Writes this type's std140 default bytes (color = white, pose = identity, ...) into a fresh
     * slab slot at the given pointer.
     */
    LongConsumer seed();

    /**
     * <p>The vertex shader of an InstanceType is responsible for transforming vertices from mesh
     * space to world space in whatever way the instance type requires.
     *
     * @return The vertex shader for this instance type.
     * @apiNote {@code flywheel/} is implicitly prepended to the {@link Identifier}'s path.
     */
    Identifier vertexShader();

    /**
     * The cull shader of this instance type.
     *
     * <p>The cull shader of an InstanceType is responsible for transforming bounding spheres from mesh
     * space to world space, such that a mesh contained by the input bounding sphere and transformed
     * by the vertex shader would be contained by the output bounding sphere.
     *
     * @return The cull shader for this instance type.
     * @apiNote {@code flywheel/} is implicitly prepended to the {@link Identifier}'s path.
     */
    Identifier cullShader();
}
