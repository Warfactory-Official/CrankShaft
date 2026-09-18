package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.world.item.Item;
import org.joml.Vector4fc;

import java.util.List;

/**
 * The item a model draws, for shaderpack guests that report item ids ({@link
 * dev.engine_room.flywheel.lib.model.baked.BakedMesh#item} covers baked item models). Equal delegate and item share
 * an instancer.
 */
public record ItemTaggedModel(Model delegate, Item item) implements Model {
    @Override
    public List<ConfiguredMesh> meshes() {
        return delegate.meshes();
    }

    @Override
    public Vector4fc boundingSphere() {
        return delegate.boundingSphere();
    }
}
