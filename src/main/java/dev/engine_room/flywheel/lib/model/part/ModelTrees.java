package dev.engine_room.flywheel.lib.model.part;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.model.baked.ModelBaseConverter;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.client.model.ModelRenderer;

import java.util.List;
import java.util.function.Supplier;

/**
 * 1.12.2: builds a {@code ModelTree} directly from a {@code ModelRenderer} hierarchy. No
 * {@code MeshTree} intermediate since {@link ModelBaseConverter} already bakes
 * {@code ModelRenderer} cubes to a {@code Mesh} in the local frame.
 */
public final class ModelTrees {
    private static final RendererReloadCache<CacheKey, ModelTree> CACHE = new RendererReloadCache<>(k -> {
        ModelRenderer renderer = k.factory().get();
        return ofRenderer(renderer, k.material());
    });

    private ModelTrees() {
    }

    /**
     * Build a fresh ModelTree from {@code renderer}. No caching — every call re-bakes every cube
     * to a Mesh (native memory). Cache the result at the call site (e.g. a static field), or use
     * {@link #of(Object, Supplier, Material)} for a reload-invalidated cache.
     */
    public static ModelTree of(ModelRenderer renderer, Material material) {
        return ofRenderer(renderer, material);
    }

    /**
     * Cached overload. {@code key} is any value with stable equals/hashCode (e.g. a String name or
     * ResourceLocation); {@code factory} is invoked only on cache miss. The cache is cleared on
     * renderer reload, so meshes always reference current atlas coordinates.
     */
    public static ModelTree of(Object key, Supplier<ModelRenderer> factory, Material material) {
        return CACHE.get(new CacheKey(key, factory, material));
    }

    private static final ModelTree[] NO_CHILDREN = new ModelTree[0];

    private static ModelTree ofRenderer(ModelRenderer renderer, Material material) {
        Mesh mesh = ModelBaseConverter.bake(renderer);
        SingleMeshModel model = mesh != null ? new SingleMeshModel(mesh, material) : null;

        // 1.12.2: ModelRenderer.childModels is lazily allocated (null until first addChild).
        List<ModelRenderer> kids = renderer.childModels;
        ModelTree[] children;
        if (kids == null || kids.isEmpty()) {
            children = NO_CHILDREN;
        } else {
            children = new ModelTree[kids.size()];
            for (int i = 0; i < kids.size(); i++) {
                children[i] = ofRenderer(kids.get(i), material);
            }
        }

        return new ModelTree(model, PartPose.fromRenderer(renderer), children);
    }

    private record CacheKey(Object id, Supplier<ModelRenderer> factory, Material material) {
        @Override
        public boolean equals(Object o) {
            return o instanceof CacheKey k && id.equals(k.id) && material.equals(k.material);
        }

        @Override
        public int hashCode() {
            return id.hashCode() * 31 + material.hashCode();
        }
    }
}
