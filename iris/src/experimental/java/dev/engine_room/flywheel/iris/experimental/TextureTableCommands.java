package dev.engine_room.flywheel.iris.experimental;

/**
 * Vulkan: binds flywheel's texture table at {@code set} of the bound pipeline's layout. After each pipeline bind.
 */
public interface TextureTableCommands {
    void flywheel$bindTextureTable(int set);
}
