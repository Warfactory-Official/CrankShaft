package dev.engine_room.flywheel.impl.extension;

/** Cold-created exact-format native pipeline variants, owned by the original Vulkan pipeline. */
public interface VulkanStencilPipelineAccess {
    void flywheel$stencilPipelines(long d24, long d32, int preferredFormat);

    boolean flywheel$hasStencilPipelines();

    long flywheel$stencilPipeline(int format);
}
