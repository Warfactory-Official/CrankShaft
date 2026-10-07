package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.Binding;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VkCaps {
    public static boolean DRAW_INDIRECT_FIRST_INSTANCE_SUPPORTED;
    public static boolean DRAW_INDIRECT_COUNT_SUPPORTED;
    public static boolean BUFFER_DEVICE_ADDRESS_SUPPORTED;
    public static boolean FRAGMENT_STORES_AND_ATOMICS_SUPPORTED;
    public static boolean INDEPENDENT_BLEND_SUPPORTED;
    public static boolean MESH_SHADER_SUPPORTED;
    public static boolean MESH_F16_VARYINGS_SUPPORTED;
    public static boolean REPRESENTATIVE_FRAGMENT_TEST_SUPPORTED;
    public static boolean DESCRIPTOR_BUFFER_SUPPORTED;
    public static boolean BINDLESS_TEXTURES_SUPPORTED;
    public static boolean FRAGMENT_SHADER_INTERLOCK_SUPPORTED;
    public static boolean DYNAMIC_RENDERING_LOCAL_READ_SUPPORTED;

    public static boolean DRAW_INDIRECT_FIRST_INSTANCE_NEGOTIATED;
    public static boolean DRAW_INDIRECT_COUNT_NEGOTIATED;
    public static boolean BUFFER_DEVICE_ADDRESS_NEGOTIATED;
    public static boolean FRAGMENT_STORES_AND_ATOMICS_NEGOTIATED;
    public static boolean INDEPENDENT_BLEND_NEGOTIATED;
    public static boolean MESH_SHADER_NEGOTIATED;
    public static boolean MESH_F16_VARYINGS_NEGOTIATED;
    public static boolean REPRESENTATIVE_FRAGMENT_TEST_NEGOTIATED;
    public static boolean DESCRIPTOR_BUFFER_NEGOTIATED;
    public static boolean DEVICE_FAULT_NEGOTIATED;
    public static boolean DEVICE_FAULT_VENDOR_BINARY_NEGOTIATED;
    public static boolean BINDLESS_TEXTURES_NEGOTIATED;
    public static boolean FRAGMENT_SHADER_INTERLOCK_NEGOTIATED;
    public static boolean DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED;

    public static boolean GPU_INSTANCE_CULL;
    public static boolean MULTI_MIP_HIZ;
    public static boolean SINGLE_MIP_HIZ;
    public static boolean GPU_OPAQUE_TERRAIN;
    public static boolean GPU_TRANSLUCENT_CULL;

    public static long DB_OFFSET_ALIGNMENT;
    public static int DB_UNIFORM_BUFFER_SIZE;
    public static int DB_STORAGE_BUFFER_SIZE;
    public static int DB_COMBINED_IMAGE_SAMPLER_SIZE;
    public static int DB_STORAGE_IMAGE_SIZE;
    public static int DB_INPUT_ATTACHMENT_SIZE;
    public static long DB_MAX_RESOURCE_RANGE;
    public static long DB_MAX_SAMPLER_RANGE;

    public static int SUBGROUP_SIZE = 32;
    public static boolean SUBGROUP_BALLOT;
    public static int BINDLESS_TABLE_CAPACITY;
    public static int MESH_MAX_WORKGROUP_COUNT_X = 65535;
    public static int MESH_MAX_OUTPUT_VERTICES = 256;
    public static int MESH_MAX_OUTPUT_PRIMITIVES = 256;

    public static int MAX_COMPUTE_WORK_GROUP_INVOCATIONS;
    public static int MAX_COMPUTE_WORK_GROUP_SIZE_X;
    public static int MAX_COMPUTE_WORK_GROUP_SIZE_Y;
    public static int MAX_COMPUTE_WORK_GROUP_SIZE_Z;
    public static long MAX_COMPUTE_WORK_GROUP_COUNT_X;
    public static long MAX_COMPUTE_WORK_GROUP_COUNT_Y;
    public static long MAX_COMPUTE_WORK_GROUP_COUNT_Z;
    public static long MAX_COMPUTE_SHARED_MEMORY_SIZE;
    public static long MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS;
    public static long MAX_PER_STAGE_DESCRIPTOR_STORAGE_IMAGES;
    public static long MAX_PER_STAGE_DESCRIPTOR_INPUT_ATTACHMENTS;
    public static long MAX_COLOR_ATTACHMENTS;
    public static long MAX_PUSH_DESCRIPTORS;
    public static long MAX_DRAW_INDIRECT_COUNT;
    public static long MAX_UNIFORM_BUFFER_RANGE;
    public static long MAX_STORAGE_BUFFER_RANGE;
    public static long MAX_MEMORY_ALLOCATION_SIZE;

    private static final int DESCRIPTOR_SAMPLER = 0;
    private static final int DESCRIPTOR_UNIFORM = 1;
    private static final int DESCRIPTOR_STORAGE = 2;
    private static final int DESCRIPTOR_SAMPLED_IMAGE = 3;
    private static final int DESCRIPTOR_STORAGE_IMAGE = 4;
    private static final int DESCRIPTOR_INPUT_ATTACHMENT = 5;
    private static final int DESCRIPTOR_RESOURCE = 6;
    private static final String[] DESCRIPTOR_NAMES = {"samplers", "uniform buffers", "storage buffers",
            "sampled images", "storage images", "input attachments", "resources"};
    private static final String[] STAGE_NAMES = {"Vertex", "Tessellation control", "Tessellation evaluation",
            "Geometry", "Fragment", "Compute", "Task", "Mesh"};
    private static final long[] PER_STAGE_DESCRIPTORS = new long[7];
    private static final long[] SET_DESCRIPTORS = new long[6];
    private static final ConcurrentMap<Integer, FormatFeatures> FORMATS = new ConcurrentHashMap<>();
    private static @Nullable VkPhysicalDevice physicalDevice;
    private static long maxPushConstantsSize;
    private static long maxVertexInputAttributes;
    private static long maxVertexInputBindings;
    private static long maxVertexInputBindingStride;
    private static long maxVertexInputAttributeOffset;

    private VkCaps() {
    }

    static void initialize(VulkanPhysicalDevice device) {
        physicalDevice = device.vkPhysicalDevice();
        FORMATS.clear();
    }

    static void initializeLimits(VkPhysicalDeviceLimits limits, long maxAllocationSize) {
        MAX_COMPUTE_WORK_GROUP_INVOCATIONS = positiveInt(limits.maxComputeWorkGroupInvocations());
        MAX_COMPUTE_WORK_GROUP_SIZE_X = positiveInt(limits.maxComputeWorkGroupSize(0));
        MAX_COMPUTE_WORK_GROUP_SIZE_Y = positiveInt(limits.maxComputeWorkGroupSize(1));
        MAX_COMPUTE_WORK_GROUP_SIZE_Z = positiveInt(limits.maxComputeWorkGroupSize(2));
        MAX_COMPUTE_WORK_GROUP_COUNT_X = unsigned(limits.maxComputeWorkGroupCount(0));
        MAX_COMPUTE_WORK_GROUP_COUNT_Y = unsigned(limits.maxComputeWorkGroupCount(1));
        MAX_COMPUTE_WORK_GROUP_COUNT_Z = unsigned(limits.maxComputeWorkGroupCount(2));
        MAX_COMPUTE_SHARED_MEMORY_SIZE = unsigned(limits.maxComputeSharedMemorySize());
        MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS = unsigned(limits.maxPerStageDescriptorStorageBuffers());
        MAX_PER_STAGE_DESCRIPTOR_STORAGE_IMAGES = unsigned(limits.maxPerStageDescriptorStorageImages());
        MAX_PER_STAGE_DESCRIPTOR_INPUT_ATTACHMENTS = unsigned(limits.maxPerStageDescriptorInputAttachments());
        MAX_COLOR_ATTACHMENTS = unsigned(limits.maxColorAttachments());
        MAX_DRAW_INDIRECT_COUNT = unsigned(limits.maxDrawIndirectCount());
        MAX_UNIFORM_BUFFER_RANGE = unsigned(limits.maxUniformBufferRange());
        MAX_STORAGE_BUFFER_RANGE = unsigned(limits.maxStorageBufferRange());
        MAX_MEMORY_ALLOCATION_SIZE = unsignedSize(maxAllocationSize);
        PER_STAGE_DESCRIPTORS[DESCRIPTOR_SAMPLER] = unsigned(limits.maxPerStageDescriptorSamplers());
        PER_STAGE_DESCRIPTORS[DESCRIPTOR_UNIFORM] = unsigned(limits.maxPerStageDescriptorUniformBuffers());
        PER_STAGE_DESCRIPTORS[DESCRIPTOR_SAMPLED_IMAGE] = unsigned(limits.maxPerStageDescriptorSampledImages());
        PER_STAGE_DESCRIPTORS[DESCRIPTOR_RESOURCE] = unsigned(limits.maxPerStageResources());
        SET_DESCRIPTORS[DESCRIPTOR_SAMPLER] = unsigned(limits.maxDescriptorSetSamplers());
        SET_DESCRIPTORS[DESCRIPTOR_UNIFORM] = unsigned(limits.maxDescriptorSetUniformBuffers());
        SET_DESCRIPTORS[DESCRIPTOR_STORAGE] = unsigned(limits.maxDescriptorSetStorageBuffers());
        SET_DESCRIPTORS[DESCRIPTOR_SAMPLED_IMAGE] = unsigned(limits.maxDescriptorSetSampledImages());
        SET_DESCRIPTORS[DESCRIPTOR_STORAGE_IMAGE] = unsigned(limits.maxDescriptorSetStorageImages());
        SET_DESCRIPTORS[DESCRIPTOR_INPUT_ATTACHMENT] = unsigned(limits.maxDescriptorSetInputAttachments());
        maxPushConstantsSize = unsigned(limits.maxPushConstantsSize());
        maxVertexInputAttributes = unsigned(limits.maxVertexInputAttributes());
        maxVertexInputBindings = unsigned(limits.maxVertexInputBindings());
        maxVertexInputBindingStride = unsigned(limits.maxVertexInputBindingStride());
        maxVertexInputAttributeOffset = unsigned(limits.maxVertexInputAttributeOffset());
    }

    static void initializeRoutes(boolean enabled) {
        GPU_INSTANCE_CULL = enabled && DRAW_INDIRECT_FIRST_INSTANCE_NEGOTIATED
                && MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS >= 6
                && canComputeWorkgroup(32, 1, 1) && canComputeWorkgroup(SUBGROUP_SIZE, 1, 1);
        int hizFeatures = VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        boolean hizFormat = enabled && supportsFormat(VK10.VK_FORMAT_R32_SFLOAT, hizFeatures);
        MULTI_MIP_HIZ = hizFormat && MAX_PER_STAGE_DESCRIPTOR_STORAGE_IMAGES >= 7
                && canComputeWorkgroup(256, 1, 1) && MAX_COMPUTE_SHARED_MEMORY_SIZE >= 1024;
        SINGLE_MIP_HIZ = hizFormat && MAX_PER_STAGE_DESCRIPTOR_STORAGE_IMAGES >= 2
                && PER_STAGE_DESCRIPTORS[DESCRIPTOR_SAMPLER] >= 1
                && PER_STAGE_DESCRIPTORS[DESCRIPTOR_SAMPLED_IMAGE] >= 1
                && canComputeWorkgroup(8, 8, 1);
        boolean terrainCommands = enabled && BUFFER_DEVICE_ADDRESS_NEGOTIATED
                && DRAW_INDIRECT_COUNT_NEGOTIATED && DRAW_INDIRECT_FIRST_INSTANCE_NEGOTIATED;
        GPU_OPAQUE_TERRAIN = terrainCommands && MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS >= 9
                && canComputeWorkgroup(256, 1, 1);
        GPU_TRANSLUCENT_CULL = terrainCommands && MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS >= 4
                && canComputeWorkgroup(64, 1, 1);
    }

    static long unsigned(int value) {
        return Integer.toUnsignedLong(value);
    }

    static long unsignedSize(long value) {
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static int positiveInt(int value) {
        return (int) Math.min(unsigned(value), Integer.MAX_VALUE);
    }

    public static boolean canComputeWorkgroup(int x, int y, int z) {
        long xy = (long) x * y;
        return x > 0 && y > 0 && z > 0
                && x <= MAX_COMPUTE_WORK_GROUP_SIZE_X && y <= MAX_COMPUTE_WORK_GROUP_SIZE_Y
                && z <= MAX_COMPUTE_WORK_GROUP_SIZE_Z && xy <= MAX_COMPUTE_WORK_GROUP_INVOCATIONS
                && xy * z <= MAX_COMPUTE_WORK_GROUP_INVOCATIONS;
    }

    public static void requireComputeWorkgroup(String label, int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("Compute workgroup must be positive");
        if (!canComputeWorkgroup(x, y, z)) {
            throw new BackendUnavailableException(label + " requires compute workgroup " + x + "x" + y + "x" + z
                    + "; device limits " + MAX_COMPUTE_WORK_GROUP_SIZE_X + "x" + MAX_COMPUTE_WORK_GROUP_SIZE_Y
                    + "x" + MAX_COMPUTE_WORK_GROUP_SIZE_Z + ", " + MAX_COMPUTE_WORK_GROUP_INVOCATIONS + " invocations");
        }
    }

    public static void requireComputeDispatch(String label, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("Compute dispatch must be nonnegative");
        if (x > MAX_COMPUTE_WORK_GROUP_COUNT_X || y > MAX_COMPUTE_WORK_GROUP_COUNT_Y
                || z > MAX_COMPUTE_WORK_GROUP_COUNT_Z) {
            throw new BackendUnavailableException(label + " requires dispatch " + x + "x" + y + "x" + z
                    + "; device limit " + MAX_COMPUTE_WORK_GROUP_COUNT_X + "x" + MAX_COMPUTE_WORK_GROUP_COUNT_Y
                    + "x" + MAX_COMPUTE_WORK_GROUP_COUNT_Z);
        }
    }

    public static void requireStorageBufferRange(String label, long bytes) {
        requireLimit(label + " storage buffer range", bytes, MAX_STORAGE_BUFFER_RANGE);
    }

    public static void checkPipelineResult(int result, String operation) {
        if (result == VK10.VK_SUCCESS) return;
        String message = "Vulkan error " + result + " creating " + operation;
        if (result == VK10.VK_ERROR_OUT_OF_HOST_MEMORY || result == VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY
                || result == VK10.VK_ERROR_DEVICE_LOST) {
            throw new IllegalStateException(message);
        }
        throw new BackendUnavailableException(message);
    }

    public static long bufferSizeLimit(int usage) {
        long limit = MAX_MEMORY_ALLOCATION_SIZE;
        if ((usage & VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0) {
            limit = Math.min(limit, MAX_STORAGE_BUFFER_RANGE);
        }
        if ((usage & VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) != 0) {
            limit = Math.min(limit, MAX_UNIFORM_BUFFER_RANGE);
        }
        if ((usage & EXTDescriptorBuffer.VK_BUFFER_USAGE_RESOURCE_DESCRIPTOR_BUFFER_BIT_EXT) != 0) {
            limit = Math.min(limit, DB_MAX_RESOURCE_RANGE);
        }
        if ((usage & EXTDescriptorBuffer.VK_BUFFER_USAGE_SAMPLER_DESCRIPTOR_BUFFER_BIT_EXT) != 0) {
            limit = Math.min(limit, DB_MAX_SAMPLER_RANGE);
        }
        return limit;
    }

    public static void requireBufferSize(int usage, long bytes) {
        if (bytes <= 0L) throw new IllegalArgumentException("Buffer size must be positive: " + bytes);
        if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0 && !BUFFER_DEVICE_ADDRESS_NEGOTIATED) {
            throw new BackendUnavailableException("Buffer device address is not enabled");
        }
        int descriptorUsage = EXTDescriptorBuffer.VK_BUFFER_USAGE_RESOURCE_DESCRIPTOR_BUFFER_BIT_EXT
                | EXTDescriptorBuffer.VK_BUFFER_USAGE_SAMPLER_DESCRIPTOR_BUFFER_BIT_EXT;
        if ((usage & descriptorUsage) != 0 && !DESCRIPTOR_BUFFER_NEGOTIATED) {
            throw new BackendUnavailableException("Descriptor buffers are not enabled");
        }
        requireLimit("Vulkan buffer size", bytes, bufferSizeLimit(usage));
    }

    public static boolean supportsFormat(int format, int requiredOptimalFeatures) {
        return (formatFeatures(format).optimal() & requiredOptimalFeatures) == requiredOptimalFeatures;
    }

    public static void requireFormat(String label, int format, int requiredOptimalFeatures) {
        int available = formatFeatures(format).optimal();
        if ((available & requiredOptimalFeatures) != requiredOptimalFeatures) {
            throw new BackendUnavailableException(label + " format " + format + " requires optimal features 0x"
                    + Integer.toHexString(requiredOptimalFeatures) + "; device has 0x" + Integer.toHexString(available));
        }
    }

    public static void requireVertexInput(VkPipelineVertexInputStateCreateInfo input) {
        var bindings = input.pVertexBindingDescriptions();
        if (bindings != null) {
            requireLimit("Vertex bindings", bindings.remaining(), maxVertexInputBindings);
            for (int i = 0; i < bindings.remaining(); i++) {
                var binding = bindings.get(i);
                requireLimit("Vertex binding index", unsigned(binding.binding()) + 1L, maxVertexInputBindings);
                requireLimit("Vertex binding stride", unsigned(binding.stride()), maxVertexInputBindingStride);
            }
        }
        var attributes = input.pVertexAttributeDescriptions();
        if (attributes != null) {
            requireLimit("Vertex attributes", attributes.remaining(), maxVertexInputAttributes);
            for (int i = 0; i < attributes.remaining(); i++) {
                var attribute = attributes.get(i);
                requireLimit("Vertex attribute location", unsigned(attribute.location()) + 1L, maxVertexInputAttributes);
                requireLimit("Vertex attribute offset", unsigned(attribute.offset()), maxVertexInputAttributeOffset);
                if ((formatFeatures(attribute.format()).buffer() & VK10.VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) == 0) {
                    throw new BackendUnavailableException("Vertex format " + attribute.format() + " is unsupported");
                }
            }
        }
    }

    public static void requireDescriptorLayout(List<Binding> bindings, int pushConstantSize, int pushConstantStages,
                                                boolean descriptorBuffer) {
        if (pushConstantSize < 0 || (pushConstantSize & 3) != 0
                || pushConstantSize > 0 && pushConstantStages == 0) {
            throw new IllegalArgumentException("Invalid push constant range: " + pushConstantSize);
        }
        requireLimit("Push constant size", pushConstantSize, maxPushConstantsSize);
        if (!descriptorBuffer) requireLimit("Push descriptors", bindings.size(), MAX_PUSH_DESCRIPTORS);
        long[] total = new long[7];
        long[][] perStage = new long[8][7];
        var declared = new HashSet<Integer>();
        for (Binding binding : bindings) {
            if (binding.binding() < 0 || binding.stageFlags() == 0 || !declared.add(binding.binding())) {
                throw new IllegalArgumentException("Invalid descriptor binding: " + binding);
            }
            addDescriptor(total, binding.type());
            int stages = binding.stageFlags();
            for (int stage = 0; stage < perStage.length; stage++) {
                if ((stages & (1 << stage)) != 0) addDescriptor(perStage[stage], binding.type());
            }
        }
        for (int type = 0; type < SET_DESCRIPTORS.length; type++) {
            requireLimit("Pipeline-layout " + DESCRIPTOR_NAMES[type], total[type], SET_DESCRIPTORS[type]);
        }
        for (int stage = 0; stage < perStage.length; stage++) {
            for (int type = 0; type < PER_STAGE_DESCRIPTORS.length; type++) {
                requireLimit(STAGE_NAMES[stage] + " " + DESCRIPTOR_NAMES[type],
                        perStage[stage][type], perStageDescriptorLimit(type));
            }
        }
    }

    private static long perStageDescriptorLimit(int type) {
        return switch (type) {
            case DESCRIPTOR_STORAGE -> MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS;
            case DESCRIPTOR_STORAGE_IMAGE -> MAX_PER_STAGE_DESCRIPTOR_STORAGE_IMAGES;
            case DESCRIPTOR_INPUT_ATTACHMENT -> MAX_PER_STAGE_DESCRIPTOR_INPUT_ATTACHMENTS;
            default -> PER_STAGE_DESCRIPTORS[type];
        };
    }

    private static void addDescriptor(long[] counts, int type) {
        switch (type) {
            case VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> counts[DESCRIPTOR_UNIFORM]++;
            case VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> counts[DESCRIPTOR_STORAGE]++;
            case VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER -> {
                counts[DESCRIPTOR_SAMPLER]++;
                counts[DESCRIPTOR_SAMPLED_IMAGE]++;
            }
            case VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE -> counts[DESCRIPTOR_STORAGE_IMAGE]++;
            case VK10.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT -> counts[DESCRIPTOR_INPUT_ATTACHMENT]++;
            default -> throw new IllegalArgumentException("Unsupported descriptor type: " + type);
        }
        counts[DESCRIPTOR_RESOURCE]++;
    }

    private static FormatFeatures formatFeatures(int format) {
        return FORMATS.computeIfAbsent(format, key -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFormatProperties properties = VkFormatProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceFormatProperties(Objects.requireNonNull(physicalDevice), key, properties);
                return new FormatFeatures(properties.bufferFeatures(), properties.optimalTilingFeatures());
            }
        });
    }

    private static void requireLimit(String label, long required, long available) {
        if (required > available) {
            throw new BackendUnavailableException(label + " requires " + required + "; device limit " + available);
        }
    }

    private record FormatFeatures(int buffer, int optimal) {
    }
}
