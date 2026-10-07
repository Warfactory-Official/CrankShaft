package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.Struct;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.*;

import java.util.Collection;
import java.util.Set;

public final class VkDeviceNegotiation {
    // Vulkan validation: bindless off.
    public static final boolean NO_BINDLESS_TEXTURES = false;
    private static final boolean CRASH_DIAG = false;
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES = 49;
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES = 51;
    private static boolean deviceFaultSupported;
    private static boolean vendorBinarySupported;
    private static boolean crashDiagSupported;
    private static int robustUniformDescriptorSize;
    private static int robustStorageDescriptorSize;

    private VkDeviceNegotiation() {
    }

    public static void appendDeviceRequests(Collection<String> deviceExtensions, VulkanPhysicalDevice physicalDevice,
                                            Set<VulkanFeature> vulkanFeatures) {
        VkCaps.initialize(physicalDevice);
        boolean shaderInt64;
        boolean shaderInt16;
        boolean storageBuffer16BitAccess;
        boolean uniformAndStorageBuffer16BitAccess;
        boolean shaderInt8;
        boolean storageBuffer8BitAccess;
        boolean uniformAndStorageBuffer8BitAccess;
        boolean sampledArrayDynamic;
        boolean taskShader;
        boolean meshShader;
        boolean subgroupControl;
        boolean descriptorBuffer;
        boolean localRead;
        boolean bindless;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceDescriptorBufferFeaturesEXT dbFeatures = VkPhysicalDeviceDescriptorBufferFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR lrFeatures = VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceFaultFeaturesEXT faultFeatures = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT ilFeatures = VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceSubgroupSizeControlFeaturesEXT sgFeatures = VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV repFeatures = VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.calloc(stack).sType$Default();
            VkPhysicalDeviceDiagnosticsConfigFeaturesNV diagFeatures = VkPhysicalDeviceDiagnosticsConfigFeaturesNV.calloc(stack).sType$Default();
            repFeatures.pNext(diagFeatures.address());
            sgFeatures.pNext(repFeatures.address());
            meshFeatures.pNext(sgFeatures.address());
            ilFeatures.pNext(meshFeatures.address());
            faultFeatures.pNext(ilFeatures.address());
            lrFeatures.pNext(faultFeatures.address());
            dbFeatures.pNext(lrFeatures.address());
            features12.pNext(dbFeatures.address());
            features11.pNext(features12.address());
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(features11.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features2);
            VkPhysicalDeviceFeatures features10 = features2.features();
            VkCaps.DRAW_INDIRECT_FIRST_INSTANCE_SUPPORTED = features10.drawIndirectFirstInstance();
            VkCaps.DRAW_INDIRECT_COUNT_SUPPORTED = features12.drawIndirectCount();
            VkCaps.BUFFER_DEVICE_ADDRESS_SUPPORTED = features12.bufferDeviceAddress();
            VkCaps.FRAGMENT_STORES_AND_ATOMICS_SUPPORTED = features10.fragmentStoresAndAtomics();
            VkCaps.INDEPENDENT_BLEND_SUPPORTED = features10.independentBlend();
            shaderInt64 = features10.shaderInt64();
            shaderInt16 = features10.shaderInt16();
            storageBuffer16BitAccess = features11.storageBuffer16BitAccess();
            uniformAndStorageBuffer16BitAccess = features11.uniformAndStorageBuffer16BitAccess();
            shaderInt8 = features12.shaderInt8();
            storageBuffer8BitAccess = features12.storageBuffer8BitAccess();
            uniformAndStorageBuffer8BitAccess = features12.uniformAndStorageBuffer8BitAccess();
            sampledArrayDynamic = features10.shaderSampledImageArrayDynamicIndexing();
            VkCaps.MESH_F16_VARYINGS_SUPPORTED = features11.storageInputOutput16() && features12.shaderFloat16();
            taskShader = meshFeatures.taskShader();
            meshShader = meshFeatures.meshShader();
            subgroupControl = sgFeatures.subgroupSizeControl();
            VkCaps.REPRESENTATIVE_FRAGMENT_TEST_SUPPORTED = repFeatures.representativeFragmentTest()
                    && physicalDevice.hasDeviceExtension(NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
            descriptorBuffer = dbFeatures.descriptorBuffer() && VkCaps.BUFFER_DEVICE_ADDRESS_SUPPORTED
                    && physicalDevice.hasDeviceExtension(EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
            localRead = lrFeatures.dynamicRenderingLocalRead() && VkCaps.INDEPENDENT_BLEND_SUPPORTED
                    && physicalDevice.hasDeviceExtension(KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
            deviceFaultSupported = faultFeatures.deviceFault()
                    && physicalDevice.hasDeviceExtension(EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            vendorBinarySupported = deviceFaultSupported && faultFeatures.deviceFaultVendorBinary();
            crashDiagSupported = CRASH_DIAG && diagFeatures.diagnosticsConfig()
                    && physicalDevice.hasDeviceExtension(NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
            VkCaps.FRAGMENT_SHADER_INTERLOCK_SUPPORTED = ilFeatures.fragmentShaderPixelInterlock()
                    && VkCaps.FRAGMENT_STORES_AND_ATOMICS_SUPPORTED
                    && physicalDevice.hasDeviceExtension(EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
            bindless = features12.shaderSampledImageArrayNonUniformIndexing()
                    && features12.descriptorBindingSampledImageUpdateAfterBind()
                    && features12.descriptorBindingPartiallyBound()
                    && features12.descriptorBindingUpdateUnusedWhilePending();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Properties props12 = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default();
            VkPhysicalDevicePushDescriptorPropertiesKHR push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceSubgroupSizeControlPropertiesEXT control = VkPhysicalDeviceSubgroupSizeControlPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMeshShaderPropertiesEXT mesh = VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceDescriptorBufferPropertiesEXT db = VkPhysicalDeviceDescriptorBufferPropertiesEXT.calloc(stack).sType$Default();
            mesh.pNext(db.address());
            control.pNext(mesh.address());
            push.pNext(control.address());
            props12.pNext(push.address());
            subgroup.pNext(props12.address());
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(subgroup.address());
            VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), properties);
            VkCaps.initializeLimits(properties.properties().limits(), physicalDevice.vkPhysicalDeviceVulkan11Properties().maxMemoryAllocationSize());
            VkCaps.MAX_PUSH_DESCRIPTORS = VkCaps.unsigned(push.maxPushDescriptors());
            VkCaps.SUBGROUP_SIZE = subgroup.subgroupSize() > 0 ? subgroup.subgroupSize() : 32;
            int ballotOps = VK12.VK_SUBGROUP_FEATURE_BASIC_BIT | VK12.VK_SUBGROUP_FEATURE_BALLOT_BIT;
            VkCaps.SUBGROUP_BALLOT = (subgroup.supportedOperations() & ballotOps) == ballotOps
                    && (subgroup.supportedStages() & VK12.VK_SHADER_STAGE_COMPUTE_BIT) != 0;
            long uabLimit = Math.min(Math.min(VkCaps.unsigned(props12.maxPerStageDescriptorUpdateAfterBindSampledImages()),
                            VkCaps.unsigned(props12.maxDescriptorSetUpdateAfterBindSampledImages())),
                    Math.min(VkCaps.unsigned(props12.maxPerStageDescriptorUpdateAfterBindSamplers()),
                            VkCaps.unsigned(props12.maxDescriptorSetUpdateAfterBindSamplers())));
            uabLimit = Math.min(uabLimit, VkCaps.unsigned(props12.maxUpdateAfterBindDescriptorsInAllPools()));
            VkCaps.BINDLESS_TABLE_CAPACITY = (int) Math.min(uabLimit, VkBindlessTable.MAX_CAPACITY);
            VkCaps.BINDLESS_TEXTURES_SUPPORTED = bindless && !NO_BINDLESS_TEXTURES
                    && uabLimit >= VkBindlessTable.MIN_CAPACITY;
            VkCaps.DESCRIPTOR_BUFFER_SUPPORTED = descriptorBuffer;
            VkCaps.DB_OFFSET_ALIGNMENT = db.descriptorBufferOffsetAlignment();
            VkCaps.DB_UNIFORM_BUFFER_SIZE = (int) db.uniformBufferDescriptorSize();
            VkCaps.DB_STORAGE_BUFFER_SIZE = (int) db.storageBufferDescriptorSize();
            VkCaps.DB_COMBINED_IMAGE_SAMPLER_SIZE = (int) db.combinedImageSamplerDescriptorSize();
            VkCaps.DB_STORAGE_IMAGE_SIZE = (int) db.storageImageDescriptorSize();
            VkCaps.DB_INPUT_ATTACHMENT_SIZE = (int) db.inputAttachmentDescriptorSize();
            robustUniformDescriptorSize = (int) db.robustUniformBufferDescriptorSize();
            robustStorageDescriptorSize = (int) db.robustStorageBufferDescriptorSize();
            VkCaps.DB_MAX_RESOURCE_RANGE = VkCaps.unsignedSize(db.maxResourceDescriptorBufferRange());
            VkCaps.DB_MAX_SAMPLER_RANGE = VkCaps.unsignedSize(db.maxSamplerDescriptorBufferRange());
            VkCaps.DYNAMIC_RENDERING_LOCAL_READ_SUPPORTED = localRead && VkCaps.MAX_COLOR_ATTACHMENTS >= 6
                    && VkCaps.MAX_PER_STAGE_DESCRIPTOR_INPUT_ATTACHMENTS >= 5;
            int meshStages = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
            int meshOps = ballotOps | VK12.VK_SUBGROUP_FEATURE_SHUFFLE_BIT;
            VkCaps.MESH_SHADER_SUPPORTED = taskShader && meshShader && shaderInt64
                    && VkCaps.BUFFER_DEVICE_ADDRESS_SUPPORTED && subgroupControl
                    && physicalDevice.hasDeviceExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)
                    && physicalDevice.hasDeviceExtension(EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME)
                    && control.minSubgroupSize() <= 32 && control.maxSubgroupSize() >= 32
                    && (control.requiredSubgroupSizeStages() & meshStages) == meshStages
                    && (subgroup.supportedStages() & meshStages) == meshStages
                    && (subgroup.supportedOperations() & meshOps) == meshOps
                    && mesh.maxTaskWorkGroupInvocations() >= 32 && mesh.maxTaskWorkGroupSize(0) >= 32
                    && mesh.maxMeshWorkGroupInvocations() >= 64 && mesh.maxMeshWorkGroupSize(0) >= 64
                    && mesh.maxMeshOutputVertices() >= 64 && mesh.maxMeshOutputPrimitives() >= 32;
            VkCaps.MESH_MAX_WORKGROUP_COUNT_X = mesh.maxMeshWorkGroupCount(0);
            VkCaps.MESH_MAX_OUTPUT_VERTICES = Math.min(256, mesh.maxMeshOutputVertices());
            VkCaps.MESH_MAX_OUTPUT_PRIMITIVES = Math.min(512, mesh.maxMeshOutputPrimitives());
        }

        request(vulkanFeatures, VkCaps.DRAW_INDIRECT_FIRST_INSTANCE_SUPPORTED, VulkanBackend.VK10_FEATURES_STRUCT,
                "drawIndirectFirstInstance", VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE);
        request(vulkanFeatures, VkCaps.FRAGMENT_STORES_AND_ATOMICS_SUPPORTED, VulkanBackend.VK10_FEATURES_STRUCT,
                "fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
        request(vulkanFeatures, VkCaps.INDEPENDENT_BLEND_SUPPORTED, VulkanBackend.VK10_FEATURES_STRUCT,
                "independentBlend", VkPhysicalDeviceFeatures.INDEPENDENTBLEND);
        request(vulkanFeatures, VkCaps.DRAW_INDIRECT_COUNT_SUPPORTED, VulkanBackend.VK12_FEATURES_STRUCT,
                "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);
        request(vulkanFeatures, VkCaps.BUFFER_DEVICE_ADDRESS_SUPPORTED, VulkanBackend.VK12_FEATURES_STRUCT,
                "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
        request(vulkanFeatures, VkCaps.BINDLESS_TEXTURES_SUPPORTED && sampledArrayDynamic, VulkanBackend.VK10_FEATURES_STRUCT,
                "shaderSampledImageArrayDynamicIndexing", VkPhysicalDeviceFeatures.SHADERSAMPLEDIMAGEARRAYDYNAMICINDEXING);
        if (deviceFaultSupported) deviceExtensions.add(EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
        if (crashDiagSupported) deviceExtensions.add(NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
        if (VkCaps.DESCRIPTOR_BUFFER_SUPPORTED) deviceExtensions.add(EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
        if (VkCaps.DYNAMIC_RENDERING_LOCAL_READ_SUPPORTED) {
            deviceExtensions.add(KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
        }
        if (VkCaps.FRAGMENT_SHADER_INTERLOCK_SUPPORTED) {
            deviceExtensions.add(EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
        }
        if (VkCaps.MESH_SHADER_SUPPORTED) {
            deviceExtensions.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            deviceExtensions.add(EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
            request(vulkanFeatures, true, VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
            request(vulkanFeatures, shaderInt16, VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt16", VkPhysicalDeviceFeatures.SHADERINT16);
            request(vulkanFeatures, storageBuffer16BitAccess, VulkanBackend.VK11_FEATURES_STRUCT,
                    "storageBuffer16BitAccess", VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS);
            request(vulkanFeatures, uniformAndStorageBuffer16BitAccess, VulkanBackend.VK11_FEATURES_STRUCT,
                    "uniformAndStorageBuffer16BitAccess", VkPhysicalDeviceVulkan11Features.UNIFORMANDSTORAGEBUFFER16BITACCESS);
            request(vulkanFeatures, shaderInt8, VulkanBackend.VK12_FEATURES_STRUCT, "shaderInt8", VkPhysicalDeviceVulkan12Features.SHADERINT8);
            request(vulkanFeatures, storageBuffer8BitAccess, VulkanBackend.VK12_FEATURES_STRUCT,
                    "storageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS);
            request(vulkanFeatures, uniformAndStorageBuffer8BitAccess, VulkanBackend.VK12_FEATURES_STRUCT,
                    "uniformAndStorageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS);
            if (VkCaps.MESH_F16_VARYINGS_SUPPORTED) {
                request(vulkanFeatures, true, VulkanBackend.VK11_FEATURES_STRUCT, "storageInputOutput16", VkPhysicalDeviceVulkan11Features.STORAGEINPUTOUTPUT16);
                request(vulkanFeatures, true, VulkanBackend.VK12_FEATURES_STRUCT, "shaderFloat16", VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);
            }
            if (VkCaps.REPRESENTATIVE_FRAGMENT_TEST_SUPPORTED) {
                deviceExtensions.add(NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
            }
        }
    }

    private static void request(Set<VulkanFeature> features, boolean supported,
                                 VulkanPNextStruct struct, String name, int offset) {
        if (supported) features.add(new VulkanFeature(struct, name, offset));
    }

    public static int createDevice(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo,
                                   VkAllocationCallbacks allocator, PointerBuffer pDevice) {
        boolean meshShader = VkCaps.MESH_SHADER_SUPPORTED
                && nameChainContainsExtension(createInfo, EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
        boolean descriptorBuffer = VkCaps.DESCRIPTOR_BUFFER_SUPPORTED
                && nameChainContainsExtension(createInfo, EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
        boolean localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_SUPPORTED
                && nameChainContainsExtension(createInfo, KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
        boolean deviceFault = deviceFaultSupported
                && nameChainContainsExtension(createInfo, EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
        boolean interlock = VkCaps.FRAGMENT_SHADER_INTERLOCK_SUPPORTED
                && nameChainContainsExtension(createInfo, EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
        boolean representativeTest = meshShader && VkCaps.REPRESENTATIVE_FRAGMENT_TEST_SUPPORTED
                && nameChainContainsExtension(createInfo, NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (deviceFault) {
                chain(createInfo, VkPhysicalDeviceFaultFeaturesEXT.calloc(stack).sType$Default()
                        .deviceFault(true).deviceFaultVendorBinary(vendorBinarySupported));
            }
            if (crashDiagSupported && nameChainContainsExtension(createInfo,
                    NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME)) {
                chain(createInfo, VkPhysicalDeviceDiagnosticsConfigFeaturesNV.calloc(stack).sType$Default().diagnosticsConfig(true));
                chain(createInfo, VkDeviceDiagnosticsConfigCreateInfoNV.calloc(stack).sType$Default()
                        .flags(NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_SHADER_DEBUG_INFO_BIT_NV
                                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_RESOURCE_TRACKING_BIT_NV
                                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_AUTOMATIC_CHECKPOINTS_BIT_NV));
            }
            if (descriptorBuffer) {
                chain(createInfo, VkPhysicalDeviceDescriptorBufferFeaturesEXT.calloc(stack).sType$Default().descriptorBuffer(true));
            }
            if (localRead) {
                chain(createInfo, VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack).sType$Default().dynamicRenderingLocalRead(true));
            }
            if (interlock) {
                chain(createInfo, VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT.calloc(stack).sType$Default().fragmentShaderPixelInterlock(true));
            }
            if (VkCaps.BINDLESS_TEXTURES_SUPPORTED) enableBindlessFeatures(createInfo, stack);
            if (meshShader) {
                chain(createInfo, VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack).sType$Default().taskShader(true).meshShader(true));
                chain(createInfo, VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.calloc(stack).sType$Default().subgroupSizeControl(true));
                if (representativeTest) {
                    chain(createInfo, VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.calloc(stack).sType$Default().representativeFragmentTest(true));
                }
            }
            int result = VK12.vkCreateDevice(physicalDevice, createInfo, allocator, pDevice);
            publish(createInfo, result == VK12.VK_SUCCESS);
            return result;
        }
    }

    private static void publish(VkDeviceCreateInfo createInfo, boolean success) {
        VkPhysicalDeviceFeatures features10 = createInfo.pEnabledFeatures();
        VkCaps.DRAW_INDIRECT_FIRST_INSTANCE_NEGOTIATED = success && VkCaps.DRAW_INDIRECT_FIRST_INSTANCE_SUPPORTED
                && features10.drawIndirectFirstInstance();
        VkCaps.FRAGMENT_STORES_AND_ATOMICS_NEGOTIATED = success && VkCaps.FRAGMENT_STORES_AND_ATOMICS_SUPPORTED
                && features10.fragmentStoresAndAtomics();
        VkCaps.INDEPENDENT_BLEND_NEGOTIATED = success && VkCaps.INDEPENDENT_BLEND_SUPPORTED && features10.independentBlend();
        VkCaps.DRAW_INDIRECT_COUNT_NEGOTIATED = success && VkCaps.DRAW_INDIRECT_COUNT_SUPPORTED
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);
        VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED = success && VkCaps.BUFFER_DEVICE_ADDRESS_SUPPORTED
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
        VkCaps.MESH_SHADER_NEGOTIATED = success && VkCaps.MESH_SHADER_SUPPORTED && VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED
                && features10.shaderInt64()
                && enabled(createInfo, EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT, VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER)
                && enabled(createInfo, EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT, VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER)
                && enabled(createInfo, EXTSubgroupSizeControl.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES_EXT,
                        VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.SUBGROUPSIZECONTROL);
        VkCaps.MESH_F16_VARYINGS_NEGOTIATED = VkCaps.MESH_SHADER_NEGOTIATED && VkCaps.MESH_F16_VARYINGS_SUPPORTED
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, VkPhysicalDeviceVulkan11Features.STORAGEINPUTOUTPUT16)
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);
        VkCaps.REPRESENTATIVE_FRAGMENT_TEST_NEGOTIATED = VkCaps.MESH_SHADER_NEGOTIATED
                && VkCaps.REPRESENTATIVE_FRAGMENT_TEST_SUPPORTED
                && enabled(createInfo, NVRepresentativeFragmentTest.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_REPRESENTATIVE_FRAGMENT_TEST_FEATURES_NV,
                        VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.REPRESENTATIVEFRAGMENTTEST);
        VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED = success && VkCaps.DESCRIPTOR_BUFFER_SUPPORTED && VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED
                && enabled(createInfo, EXTDescriptorBuffer.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_BUFFER_FEATURES_EXT,
                        VkPhysicalDeviceDescriptorBufferFeaturesEXT.DESCRIPTORBUFFER);
        if (VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED && features10.robustBufferAccess()) {
            VkCaps.DB_UNIFORM_BUFFER_SIZE = robustUniformDescriptorSize;
            VkCaps.DB_STORAGE_BUFFER_SIZE = robustStorageDescriptorSize;
        }
        VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED = success && VkCaps.DYNAMIC_RENDERING_LOCAL_READ_SUPPORTED
                && VkCaps.INDEPENDENT_BLEND_NEGOTIATED
                && enabled(createInfo, KHRDynamicRenderingLocalRead.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_LOCAL_READ_FEATURES_KHR,
                        VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.DYNAMICRENDERINGLOCALREAD);
        VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED = success && VkCaps.FRAGMENT_SHADER_INTERLOCK_SUPPORTED
                && VkCaps.FRAGMENT_STORES_AND_ATOMICS_NEGOTIATED
                && enabled(createInfo, EXTFragmentShaderInterlock.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_INTERLOCK_FEATURES_EXT,
                        VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT.FRAGMENTSHADERPIXELINTERLOCK);
        VkCaps.BINDLESS_TEXTURES_NEGOTIATED = success && VkCaps.BINDLESS_TEXTURES_SUPPORTED
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING)
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND)
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND)
                && enabled(createInfo, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUPDATEUNUSEDWHILEPENDING);
        VkCaps.DEVICE_FAULT_NEGOTIATED = success && deviceFaultSupported
                && enabled(createInfo, EXTDeviceFault.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT, VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT);
        VkCaps.DEVICE_FAULT_VENDOR_BINARY_NEGOTIATED = VkCaps.DEVICE_FAULT_NEGOTIATED && vendorBinarySupported
                && enabled(createInfo, EXTDeviceFault.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT, VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULTVENDORBINARY);
        VkCaps.initializeRoutes(success);
    }

    private static boolean enabled(VkDeviceCreateInfo createInfo, int type, int offset) {
        long address = findStruct(createInfo.pNext(), type);
        return address != 0L && MemoryUtil.memGetInt(address + offset) != 0;
    }

    public static int createVma(VmaAllocatorCreateInfo createInfo, PointerBuffer pAllocator) {
        if (VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED) {
            createInfo.flags(createInfo.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return Vma.vmaCreateAllocator(createInfo, pAllocator);
    }

    private static boolean nameChainContainsExtension(VkDeviceCreateInfo createInfo, String extension) {
        PointerBuffer names = createInfo.ppEnabledExtensionNames();
        if (names == null) return false;
        for (int i = names.position(); i < names.limit(); i++) {
            if (extension.equals(MemoryUtil.memUTF8(names.get(i)))) return true;
        }
        return false;
    }

    private static void enableBindlessFeatures(VkDeviceCreateInfo createInfo, MemoryStack stack) {
        long address = findStruct(createInfo.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
        VkPhysicalDeviceVulkan12Features features;
        if (address != 0L) {
            features = VkPhysicalDeviceVulkan12Features.create(address);
        } else {
            features = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            chain(createInfo, features);
        }
        features.shaderSampledImageArrayNonUniformIndexing(true)
                .descriptorBindingSampledImageUpdateAfterBind(true)
                .descriptorBindingPartiallyBound(true)
                .descriptorBindingUpdateUnusedWhilePending(true);
    }

    private static long findStruct(long pNextChain, int sType) {
        while (pNextChain != 0L) {
            if (MemoryUtil.memGetInt(pNextChain) == sType) return pNextChain;
            pNextChain = MemoryUtil.memGetAddress(pNextChain + Pointer.POINTER_SIZE);
        }
        return 0L;
    }

    // Compat with Caustica: duplicate sType invalid; merge feature words.
    private static void chain(VkDeviceCreateInfo createInfo, Struct<?> struct) {
        long address = struct.address();
        long existing = findStruct(createInfo.pNext(), MemoryUtil.memGetInt(address));
        if (existing == 0L) {
            MemoryUtil.memPutAddress(address + Pointer.POINTER_SIZE, createInfo.pNext());
            createInfo.pNext(address);
            return;
        }
        for (long offset = 2L * Pointer.POINTER_SIZE; offset + Integer.BYTES <= struct.sizeof(); offset += Integer.BYTES) {
            MemoryUtil.memPutInt(existing + offset,
                    MemoryUtil.memGetInt(existing + offset) | MemoryUtil.memGetInt(address + offset));
        }
    }
}
