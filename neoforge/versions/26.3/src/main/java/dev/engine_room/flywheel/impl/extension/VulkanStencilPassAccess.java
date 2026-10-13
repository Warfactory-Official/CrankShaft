package dev.engine_room.flywheel.impl.extension;

/** Records the supplied attachment format before the native pass can select a graphics pipeline. */
public interface VulkanStencilPassAccess {
    void flywheel$depthFormat(int format);
}
