package dev.engine_room.flywheel.backend.engine;

/**
 * Creates the per-instancer {@link Slab} for a backend. The GL indirect backend passes {@code GlSlab::new}; the
 * Vulkan indirect backend passes its VMA-backed slab constructor, so {@link IndirectInstancer} stays backend-neutral.
 */
@FunctionalInterface
public interface SlabFactory {
    Slab create(long pageSizeBytes, int initialPages);
}
