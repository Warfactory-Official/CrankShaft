package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.joml.Vector4fc;

import java.util.List;

/**
 * A model reporting a rendered item alongside its visual's entity or block identity. Wrap an item model or tag its
 * tree when baking equipment; the item and metadata remain unchanged until that model is rebuilt.
 */
public record ItemTaggedModel(Model delegate, Item item, int metadata) implements Model {
    public ItemTaggedModel(Model model, Item item) {
        this(model, item, 0);
    }

    public ItemTaggedModel(Model model, ItemStack stack) {
        this(model, stack.getItem(), stack.getMetadata());
    }

    public static ModelTree tag(ModelTree tree, ItemStack stack) {
        var children = new ModelTree[tree.childCount()];
        for (var index = 0; index < children.length; index++) children[index] = tag(tree.child(index), stack);
        var model = tree.model();
        return new ModelTree(model == null ? null : new ItemTaggedModel(model, stack), tree.initialPose(), children);
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
