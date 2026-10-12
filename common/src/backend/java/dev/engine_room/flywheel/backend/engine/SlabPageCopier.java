package dev.engine_room.flywheel.backend.engine;

/**
 * Backend-specific copy of a live range within one instance page from a {@link Slab} to the indirect object buffer,
 * supplied per frame by the culling group. GL snapshots into fenced upload batches and scatters by destination;
 * Vulkan snapshots into fenced frame storage and uses scatter or coalesced transfer copies. Keeping the source
 * slab as a parameter lets the copier read the backend-specific storage without
 * {@link dev.engine_room.flywheel.backend.engine.indirect.IndirectInstancer} leaking it.
 */
@FunctionalInterface
public interface SlabPageCopier {
    void copyPage(Slab source, long srcByteOffset, long dstByteOffset, long byteSize);
}
