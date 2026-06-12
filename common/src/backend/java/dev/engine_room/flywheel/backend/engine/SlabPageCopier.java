package dev.engine_room.flywheel.backend.engine;

/**
 * Backend-specific copy of one instance page from a {@link Slab} to the indirect object buffer, supplied per frame by
 * the culling group. GL issues a {@code glCopyNamedBufferSubData} per page; Vulkan accumulates {@code VkBufferCopy}
 * regions for one batched {@code vkCmdCopyBuffer}. Keeping the source slab as a parameter lets the copier read the
 * backend-specific buffer handle without {@link dev.engine_room.flywheel.backend.engine.indirect.IndirectInstancer}
 * leaking it.
 */
@FunctionalInterface
public interface SlabPageCopier {
    void copyPage(Slab source, long srcByteOffset, long dstByteOffset, long byteSize);
}
