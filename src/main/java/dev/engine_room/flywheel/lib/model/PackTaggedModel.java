package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import org.joml.Vector4fc;

import java.util.List;

/**
 * A model with shaderpack identities, best match first. Downstream renderers wrap a baked model or use
 * {@link #tag} on its tree; the identity list stays immutable while its geometry is pooled.
 */
public record PackTaggedModel(Model delegate, List<PackIdentity> identities) implements Model {
    public PackTaggedModel {
        identities = List.copyOf(identities);
    }

    public static Model unwrap(Model model) {
        while (true) {
            if (model instanceof PackTaggedModel tagged) model = tagged.delegate();
            else if (model instanceof ItemTaggedModel tagged) model = tagged.delegate();
            else return model;
        }
    }

    public static ModelTree tag(ModelTree tree, List<PackIdentity> identities) {
        var children = new ModelTree[tree.childCount()];
        for (var index = 0; index < children.length; index++) children[index] = tag(tree.child(index), identities);
        var model = tree.model();
        return new ModelTree(model == null ? null : new PackTaggedModel(model, identities), tree.initialPose(),
                children);
    }

    @Override
    public List<ConfiguredMesh> meshes() {
        return delegate.meshes();
    }

    @Override
    public Vector4fc boundingSphere() {
        return delegate.boundingSphere();
    }
}
