package dev.engine_room.flywheel.lib.model.part;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.RetexturedMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
#if MC >= 26.3
import net.minecraft.client.renderer.texture.UvMapping;
#endif
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 26.2: texture overloads take a resolved {@link TextureAtlasSprite}; atlas resolution lives at the caller (the visual).
 */
public final class ModelTrees {
    private static final RendererReloadCache<ModelTreeKey, ModelTree> CACHE = new RendererReloadCache<>(k -> {
        ModelTree tree = convert("", MeshTree.of(k.layer), k.pathsToPrune, k.sprite, k.material);

        if (tree == null) {
            throw new IllegalArgumentException("Cannot prune root node!");
        }

        return tree;
    });

    private ModelTrees() {
    }

    public static ModelTree of(ModelLayerLocation layer, Material material) {
        return CACHE.get(new ModelTreeKey(layer, Collections.emptySet(), null, material));
    }

    public static ModelTree of(ModelLayerLocation layer, TextureAtlasSprite sprite, Material material) {
        return CACHE.get(new ModelTreeKey(layer, Collections.emptySet(), sprite, material));
    }

    #if MC >= 26.3
    // Dynamic palettes prepare their mapping on the render thread; the cached mesh consumes immutable UVs.
    public static ModelTree of(ModelLayerLocation layer, UvMapping mapping, Material material) {
        return CACHE.get(new ModelTreeKey(layer, Collections.emptySet(), mapping, material));
    }

    public static ModelTree of(ModelLayerLocation layer, Set<String> pathsToPrune, UvMapping mapping,
                               Material material) {
        return CACHE.get(new ModelTreeKey(layer, Set.copyOf(pathsToPrune), mapping, material));
    }
    #endif

    public static ModelTree of(ModelLayerLocation layer, Set<String> pathsToPrune, Material material) {
        return CACHE.get(new ModelTreeKey(layer, Set.copyOf(pathsToPrune), null, material));
    }

    public static ModelTree of(ModelLayerLocation layer, Set<String> pathsToPrune, TextureAtlasSprite sprite,
                               Material material) {
        return CACHE.get(new ModelTreeKey(layer, Set.copyOf(pathsToPrune), sprite, material));
    }

    @Nullable
    private static ModelTree convert(String path, MeshTree meshTree, Set<String> pathsToPrune,
                                     #if MC >= 26.3
                                     @Nullable UvMapping sprite, Material material) {
                                     #else
                                     @Nullable TextureAtlasSprite sprite, Material material) {
                                     #endif
        if (pathsToPrune.contains(path)) {
            return null;
        }

        Model model = null;
        Mesh mesh = meshTree.mesh();

        if (mesh != null) {
            if (sprite != null) {
                mesh = new RetexturedMesh(mesh, sprite);
            }

            model = new SingleMeshModel(mesh, material);
        }

        Map<String, ModelTree> children = new HashMap<>();
        String pathSlash = path + "/";

        for (int i = 0; i < meshTree.childCount(); i++) {
            String childName = meshTree.childName(i);
            var child = convert(pathSlash + childName, meshTree.child(i), pathsToPrune, sprite, material);

            if (child != null) {
                children.put(childName, child);
            }
        }

        return new ModelTree(model, meshTree.initialPose(), children);
    }

    private record ModelTreeKey(ModelLayerLocation layer, Set<String> pathsToPrune,
                                #if MC >= 26.3
                                @Nullable UvMapping sprite,
                                #else
                                @Nullable TextureAtlasSprite sprite,
                                #endif
                                Material material) {
    }
}
