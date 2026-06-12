package dev.engine_room.flywheel.backend.vk;

import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;

/**
 * Negotiated Vulkan device capabilities, set once at device creation; a cap is true only if the device advertised AND
 * creation requested the feature, so an unsupported feature stays dormant instead of failing {@code vkCreateDevice}.
 */
public final class VkCaps {
    public static boolean DRAW_INDIRECT_COUNT_NEGOTIATED;

    public static boolean BUFFER_DEVICE_ADDRESS_NEGOTIATED;

    public static boolean MESH_SHADER_NEGOTIATED;

    public static boolean MESH_F16_VARYINGS_NEGOTIATED;

    public static boolean REPRESENTATIVE_FRAGMENT_TEST_NEGOTIATED;

    public static boolean DESCRIPTOR_BUFFER_NEGOTIATED;

    // VkPhysicalDeviceDescriptorBufferPropertiesEXT, queried at negotiation; non-robust sizes (vanilla never enables robustBufferAccess).
    public static long DB_OFFSET_ALIGNMENT;
    public static int DB_UNIFORM_BUFFER_SIZE;
    public static int DB_STORAGE_BUFFER_SIZE;
    public static int DB_COMBINED_IMAGE_SAMPLER_SIZE;
    public static int DB_STORAGE_IMAGE_SIZE;
    public static int DB_INPUT_ATTACHMENT_SIZE;

    public static int SUBGROUP_SIZE = 32;

    public static boolean DEVICE_FAULT_NEGOTIATED = false;

    public static boolean DEVICE_FAULT_VENDOR_BINARY_NEGOTIATED = false;

    public static boolean BINDLESS_TEXTURES_NEGOTIATED = false;

    public static int BINDLESS_TABLE_CAPACITY = 0;

    public static boolean SUBGROUP_BALLOT = false;

    public static int MESH_MAX_WORKGROUP_COUNT_X = 65535;
    public static int MESH_MAX_OUTPUT_VERTICES = 256;
    public static int MESH_MAX_OUTPUT_PRIMITIVES = 256;

    /**
     * {@code VK_EXT_fragment_shader_interlock}: per-pixel critical sections gate the MLAB OIT pass (the wavelet chain stays the fallback on AMD-on-Vulkan).
     */
    public static boolean FRAGMENT_SHADER_INTERLOCK_NEGOTIATED = false;

    public static boolean DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED;

    private VkCaps() {
    }
}
