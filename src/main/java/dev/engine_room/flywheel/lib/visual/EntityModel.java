package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/**
 * Adapts a vanilla {@link ModelBase} for instanced living-entity rendering.
 *
 * <p>Downstream registers a mob by pairing an {@code EntityModel} with a {@link AbstractLivingEntityVisual}
 * subclass: {@link #create()} supplies the per-visual scratch model (whose {@code setRotationAngles}
 * is driven each frame), and {@link #roots(ModelBase)} lists the top-level {@link ModelRenderer}s that
 * get baked into one shared, batched {@code ModelTree}.
 *
 * <p>The order returned by {@link #roots(ModelBase)} is the contract: the same order is used to bake
 * the cached geometry and to read back per-frame angles, so {@code roots(scratch)[i]} must always
 * correspond to the same logical bone. 1.12.2 {@code ModelBase} exposes its parts only as fields, so
 * this enumeration must be supplied per model class.
 */
public interface EntityModel<M extends ModelBase> {
    /** A fresh model instance. Called for the per-visual scratch model and (on cache miss) for baking. */
    M create();

    /** The top-level parts, in a stable order. Children are recursed via {@code ModelRenderer.childModels}. */
    ModelRenderer[] roots(M model);

    /**
     * Pre-multiply {@code dest} with vanilla's per-root baby group transform (enlarged head vs half-scale body).
     * Default: identity. The {@code 0.0625} model scale is already baked into the bones, so vanilla's
     * {@code translate(0, k*scale, 0)} terms become {@code translate(0, k*0.0625, 0)} here.
     */
    default void babyTransform(Matrix4f dest, M model, int rootIndex) {
    }

    /** Whether {@link #babyTransform} is meaningful; else a visual MUST fall back to vanilla for babies. */
    default boolean hasBabyTransform() {
        return false;
    }

    static ModelRenderer[] prepend(ModelRenderer first, ModelRenderer[] rest) {
        ModelRenderer[] out = new ModelRenderer[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    static ModelRenderer[] concat(ModelRenderer[] a, ModelRenderer[] b) {
        ModelRenderer[] out = new ModelRenderer[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
