package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.*;

import java.util.Collection;
import java.util.Set;

/**
 * All requests fail closed (advertised AND requested, recorded in {@link VkCaps}) so an unsupported feature stays
 * dormant instead of failing {@code vkCreateDevice}.
 */
public final class VkDeviceNegotiation {
    // Compile-time bindless kill-switch (flip to true + rebuild -- REQUIRED for every -Pvkvalidation run, flip back after).
    public static final boolean NO_BINDLESS_TEXTURES = false;
    private static final boolean CRASH_DIAG = false;
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES = 49;
    private static final int VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES = 51;
    private static boolean bindlessSupported;
    private static boolean f16IoSupported;

    private VkDeviceNegotiation() {
    }

    public static void appendDeviceRequests(Collection<String> deviceExtensions, VulkanPhysicalDevice physicalDevice,
                                            Set<VulkanFeature> vulkanFeatures) {
        vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "drawIndirectFirstInstance",
                VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE));

        boolean drawIndirectCount;
        boolean bufferDeviceAddress;
        boolean descriptorBuffer;
        boolean localRead;
        boolean deviceFault;
        boolean bindless;
        boolean interlock;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                                                                                          .sType$Default();
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                                                                                          .sType$Default();
            VkPhysicalDeviceDescriptorBufferFeaturesEXT dbFeatures = VkPhysicalDeviceDescriptorBufferFeaturesEXT.calloc(
                    stack).sType$Default();
            VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR lrFeatures =
                    VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceFaultFeaturesEXT faultFeatures = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack)
                                                                                             .sType$Default();
            VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT ilFeatures =
                    VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT.calloc(stack).sType$Default();
            faultFeatures.pNext(ilFeatures.address());
            lrFeatures.pNext(faultFeatures.address());
            dbFeatures.pNext(lrFeatures.address());
            features12.pNext(dbFeatures.address());
            features11.pNext(features12.address());
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            features2.pNext(features11.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features2);
            f16IoSupported = features11.storageInputOutput16() && features12.shaderFloat16();
            drawIndirectCount = features12.drawIndirectCount();
            bufferDeviceAddress = features12.bufferDeviceAddress();
            bindless = features12.shaderSampledImageArrayNonUniformIndexing()
                    && features12.descriptorBindingSampledImageUpdateAfterBind()
                    && features12.descriptorBindingPartiallyBound()
                    && features12.descriptorBindingUpdateUnusedWhilePending();
            // NV driver bug (reproduced on 610.47 and 610.62, soak-bisected 2026-07-04): GRAPHICS-stage
            // combined-image-sampler sets consumed from a descriptor buffer MMU-fault a driver-internal kernel minutes
            // into chunk-churn flight; DB_NO_GFX_SAMPLERS in VkDescriptorLayout routes that class to push descriptors, so the extension negotiates whenever supported.
            descriptorBuffer = dbFeatures.descriptorBuffer()
                    && physicalDevice.hasDeviceExtension(EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
            localRead = lrFeatures.dynamicRenderingLocalRead()
                    && features2.features().independentBlend()
                    && physicalDevice.hasDeviceExtension(
                    KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
            deviceFault = faultFeatures.deviceFault()
                    && physicalDevice.hasDeviceExtension(EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            interlock = ilFeatures.fragmentShaderPixelInterlock()
                    && features2.features().fragmentStoresAndAtomics()
                    && physicalDevice.hasDeviceExtension(
                    EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
        }
        if (deviceFault) {
            deviceExtensions.add(EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
        }
        if (interlock) {
            deviceExtensions.add(EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
            vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics",
                    VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS));
        }

        if (CRASH_DIAG && physicalDevice.hasDeviceExtension(
                NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME)) {
            deviceExtensions.add(NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
        }
        if (drawIndirectCount) {
            vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount",
                    VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT));
        }
        VkCaps.DRAW_INDIRECT_COUNT_NEGOTIATED = drawIndirectCount;
        if (bufferDeviceAddress) {
            vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                    VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
        }
        VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED = bufferDeviceAddress;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceSubgroupProperties subgroupProps = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                                                                                                 .sType$Default();
            VkPhysicalDeviceVulkan12Properties props12 = VkPhysicalDeviceVulkan12Properties.calloc(stack)
                                                                                           .sType$Default();
            subgroupProps.pNext(props12.address());
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            props2.pNext(subgroupProps.address());
            VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), props2);
            long uabLimit = Math.min(
                    Integer.toUnsignedLong(props12.maxPerStageDescriptorUpdateAfterBindSampledImages()),
                    Integer.toUnsignedLong(props12.maxDescriptorSetUpdateAfterBindSampledImages()));
            bindless = bindless && uabLimit >= VkBindlessTable.MIN_CAPACITY;
            VkCaps.BINDLESS_TABLE_CAPACITY = (int) Math.min(uabLimit, VkBindlessTable.MAX_CAPACITY);
            int subgroupSize = subgroupProps.subgroupSize();
            if (subgroupSize > 0) {
                VkCaps.SUBGROUP_SIZE = subgroupSize;
            }
            int ballotOps = VK12.VK_SUBGROUP_FEATURE_BASIC_BIT | VK12.VK_SUBGROUP_FEATURE_BALLOT_BIT;
            VkCaps.SUBGROUP_BALLOT = (subgroupProps.supportedOperations() & ballotOps) == ballotOps
                    && (subgroupProps.supportedStages() & VK12.VK_SHADER_STAGE_COMPUTE_BIT) != 0;
        }
        bindlessSupported = bindless && !NO_BINDLESS_TEXTURES;

        if (descriptorBuffer && bufferDeviceAddress) {
            deviceExtensions.add(EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceDescriptorBufferPropertiesEXT dbProps =
                        VkPhysicalDeviceDescriptorBufferPropertiesEXT.calloc(stack).sType$Default();
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(dbProps.address());
                VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), props2);
                VkCaps.DB_OFFSET_ALIGNMENT = dbProps.descriptorBufferOffsetAlignment();
                VkCaps.DB_UNIFORM_BUFFER_SIZE = (int) dbProps.uniformBufferDescriptorSize();
                VkCaps.DB_STORAGE_BUFFER_SIZE = (int) dbProps.storageBufferDescriptorSize();
                VkCaps.DB_COMBINED_IMAGE_SAMPLER_SIZE = (int) dbProps.combinedImageSamplerDescriptorSize();
                VkCaps.DB_STORAGE_IMAGE_SIZE = (int) dbProps.storageImageDescriptorSize();
                VkCaps.DB_INPUT_ATTACHMENT_SIZE = (int) dbProps.inputAttachmentDescriptorSize();
            }
        }

        if (localRead) {
            deviceExtensions.add(KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
            vulkanFeatures.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "independentBlend",
                    VkPhysicalDeviceFeatures.INDEPENDENTBLEND));
        }

        // Append mesh extensions here so createDevice sees them in the name chain; subgroup-size-control + rep-fragment-test ride along only with mesh shaders.
        if (physicalDevice.hasDeviceExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceMeshShaderPropertiesEXT meshProps =
                        VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default();
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(meshProps.address());
                VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), props2);
                VkCaps.MESH_MAX_WORKGROUP_COUNT_X = Math.max(65535, meshProps.maxMeshWorkGroupCount(0));
                VkCaps.MESH_MAX_OUTPUT_PRIMITIVES = Math.min(512, Math.max(256, meshProps.maxMeshOutputPrimitives()));
            }
            deviceExtensions.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            if (physicalDevice.hasDeviceExtension(EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME)) {
                deviceExtensions.add(EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
            }
            if (physicalDevice.hasDeviceExtension(
                    NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME)) {
                deviceExtensions.add(NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
            }
        }
    }

    /**
     * Wraps {@code vkCreateDevice}: re-detect negotiated features off the extension-name chain, chain the enable structs, and publish every cap in ONE success-gated block so a re-created device resets stale caps.
     */
    public static int createDevice(VkPhysicalDevice vkPhysicalDevice, VkDeviceCreateInfo createInfo,
                                   VkAllocationCallbacks allocator, PointerBuffer pDevice) {
        boolean meshShader = nameChainContainsExtension(createInfo, EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
        boolean descriptorBuffer = nameChainContainsExtension(createInfo,
                EXTDescriptorBuffer.VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME);
        boolean localRead = nameChainContainsExtension(createInfo,
                KHRDynamicRenderingLocalRead.VK_KHR_DYNAMIC_RENDERING_LOCAL_READ_EXTENSION_NAME);
        boolean deviceFault = nameChainContainsExtension(createInfo, EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
        boolean crashDiag = nameChainContainsExtension(createInfo,
                NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
        boolean interlock = nameChainContainsExtension(createInfo,
                EXTFragmentShaderInterlock.VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME);
        boolean bindless = bindlessSupported;
        boolean vendorBinary = false;
        boolean subgroupControl = false;
        boolean representativeTest = false;

        int result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (deviceFault) {
                VkPhysicalDeviceFaultFeaturesEXT supported = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack)
                                                                                             .sType$Default();
                VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                query.pNext(supported.address());
                VK12.vkGetPhysicalDeviceFeatures2(vkPhysicalDevice, query);
                vendorBinary = supported.deviceFaultVendorBinary();
                VkPhysicalDeviceFaultFeaturesEXT faultFeatures = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack)
                                                                                                 .sType$Default()
                                                                                                 .deviceFault(true)
                                                                                                 .deviceFaultVendorBinary(
                                                                                                         vendorBinary);
                prepend(createInfo, faultFeatures.address());
            }
            if (crashDiag) {
                VkPhysicalDeviceDiagnosticsConfigFeaturesNV diagFeatures =
                        VkPhysicalDeviceDiagnosticsConfigFeaturesNV.calloc(stack)
                                                                   .sType$Default()
                                                                   .diagnosticsConfig(true);
                prepend(createInfo, diagFeatures.address());
                VkDeviceDiagnosticsConfigCreateInfoNV diagConfig = VkDeviceDiagnosticsConfigCreateInfoNV.calloc(stack)
                                                                                                        .sType$Default()
                                                                                                        .flags(NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_SHADER_DEBUG_INFO_BIT_NV
                                                                                                                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_RESOURCE_TRACKING_BIT_NV
                                                                                                                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_AUTOMATIC_CHECKPOINTS_BIT_NV);
                prepend(createInfo, diagConfig.address());
            }
            if (descriptorBuffer) {
                VkPhysicalDeviceDescriptorBufferFeaturesEXT dbFeatures =
                        VkPhysicalDeviceDescriptorBufferFeaturesEXT.calloc(stack)
                                                                   .sType$Default()
                                                                   .descriptorBuffer(true);
                prepend(createInfo, dbFeatures.address());
            }
            if (localRead) {
                VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR lrFeatures =
                        VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack)
                                                                            .sType$Default()
                                                                            .dynamicRenderingLocalRead(true);
                prepend(createInfo, lrFeatures.address());
            }
            if (interlock) {
                VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT ilFeatures =
                        VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT.calloc(stack)
                                                                          .sType$Default()
                                                                          .fragmentShaderPixelInterlock(true);
                prepend(createInfo, ilFeatures.address());
            }
            if (bindless) {
                enableBindlessFeatures(createInfo, stack);
            }
            if (meshShader) {
                VkPhysicalDeviceFeatures features10 = createInfo.pEnabledFeatures();
                MemoryUtil.memPutInt(features10.address() + VkPhysicalDeviceFeatures.SHADERINT64, 1);
                MemoryUtil.memPutInt(features10.address() + VkPhysicalDeviceFeatures.SHADERINT16, 1);

                long vulkan11 = findStruct(createInfo.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES);
                if (vulkan11 != 0L) {
                    MemoryUtil.memPutInt(vulkan11 + VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS, 1);
                    MemoryUtil.memPutInt(vulkan11 + VkPhysicalDeviceVulkan11Features.UNIFORMANDSTORAGEBUFFER16BITACCESS,
                            1);
                    MemoryUtil.memPutInt(vulkan11 + VkPhysicalDeviceVulkan11Features.SHADERDRAWPARAMETERS, 1);
                    if (f16IoSupported) {
                        MemoryUtil.memPutInt(vulkan11 + VkPhysicalDeviceVulkan11Features.STORAGEINPUTOUTPUT16, 1);
                    }
                } else {
                    VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                                                                                                  .sType$Default()
                                                                                                  .storageBuffer16BitAccess(
                                                                                                          true)
                                                                                                  .uniformAndStorageBuffer16BitAccess(
                                                                                                          true)
                                                                                                  .shaderDrawParameters(
                                                                                                          true)
                                                                                                  .storageInputOutput16(
                                                                                                          f16IoSupported);
                    prepend(createInfo, features11.address());
                }

                long vulkan12 = findStruct(createInfo.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
                if (vulkan12 != 0L) {
                    MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS, 1);
                    MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.SHADERINT8, 1);
                    MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS, 1);
                    MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS,
                            1);
                    if (f16IoSupported) {
                        MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.SHADERFLOAT16, 1);
                    }
                } else {
                    VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                                                                                                  .sType$Default()
                                                                                                  .bufferDeviceAddress(
                                                                                                          true)
                                                                                                  .shaderInt8(true)
                                                                                                  .storageBuffer8BitAccess(
                                                                                                          true)
                                                                                                  .uniformAndStorageBuffer8BitAccess(
                                                                                                          true)
                                                                                                  .shaderFloat16(
                                                                                                          f16IoSupported);
                    prepend(createInfo, features12.address());
                }

                VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                                                                                                          .sType$Default()
                                                                                                          .taskShader(
                                                                                                                  true)
                                                                                                          .meshShader(
                                                                                                                  true);
                prepend(createInfo, meshFeatures.address());

                subgroupControl = nameChainContainsExtension(createInfo,
                        EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
                if (subgroupControl) {
                    VkPhysicalDeviceSubgroupSizeControlFeaturesEXT subgroupFeatures =
                            VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.calloc(stack)
                                                                          .sType$Default()
                                                                          .subgroupSizeControl(true);
                    prepend(createInfo, subgroupFeatures.address());
                }

                representativeTest = nameChainContainsExtension(createInfo,
                        NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
                if (representativeTest) {
                    VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV repFeatures =
                            VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.calloc(stack)
                                                                                .sType$Default()
                                                                                .representativeFragmentTest(true);
                    prepend(createInfo, repFeatures.address());
                }
            }

            result = VK12.vkCreateDevice(vkPhysicalDevice, createInfo, allocator, pDevice);
        }

        boolean ok = result == VK12.VK_SUCCESS;
        // Mesh + the 32-lane pin must BOTH negotiate: the subgroup reductions are only correct at size 32 -- else the tier stays dormant.
        VkCaps.MESH_SHADER_NEGOTIATED = ok && subgroupControl;
        VkCaps.MESH_F16_VARYINGS_NEGOTIATED = ok && meshShader && f16IoSupported;
        VkCaps.REPRESENTATIVE_FRAGMENT_TEST_NEGOTIATED = ok && representativeTest;
        VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED = ok && descriptorBuffer;
        VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED = ok && localRead;
        VkCaps.DEVICE_FAULT_NEGOTIATED = ok && deviceFault;
        VkCaps.DEVICE_FAULT_VENDOR_BINARY_NEGOTIATED = ok && vendorBinary;
        VkCaps.BINDLESS_TEXTURES_NEGOTIATED = ok && bindless;
        VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED = ok && interlock;
        return result;
    }

    public static int createVma(VmaAllocatorCreateInfo createInfo, PointerBuffer pAllocator) {
        if (VkCaps.MESH_SHADER_NEGOTIATED || VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED) {
            createInfo.flags(createInfo.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return Vma.vmaCreateAllocator(createInfo, pAllocator);
    }

    private static boolean nameChainContainsExtension(VkDeviceCreateInfo createInfo, String extension) {
        PointerBuffer names = createInfo.ppEnabledExtensionNames();
        if (names == null) {
            return false;
        }
        for (int i = 0; i < names.remaining(); i++) {
            if (extension.equals(MemoryUtil.memUTF8(names.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private static void enableBindlessFeatures(VkDeviceCreateInfo createInfo, MemoryStack stack) {
        MemoryUtil.memPutInt(createInfo.pEnabledFeatures().address()
                + VkPhysicalDeviceFeatures.SHADERSAMPLEDIMAGEARRAYDYNAMICINDEXING, 1);
        long vulkan12 = findStruct(createInfo.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
        if (vulkan12 != 0L) {
            MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING,
                    1);
            MemoryUtil.memPutInt(
                    vulkan12 + VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND, 1);
            MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND, 1);
            MemoryUtil.memPutInt(vulkan12 + VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUPDATEUNUSEDWHILEPENDING,
                    1);
        } else {
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                                                                                          .sType$Default()
                                                                                          .shaderSampledImageArrayNonUniformIndexing(
                                                                                                  true)
                                                                                          .descriptorBindingSampledImageUpdateAfterBind(
                                                                                                  true)
                                                                                          .descriptorBindingPartiallyBound(
                                                                                                  true)
                                                                                          .descriptorBindingUpdateUnusedWhilePending(
                                                                                                  true);
            prepend(createInfo, features12.address());
        }
    }

    private static long findStruct(long pNextChain, int sType) {
        while (pNextChain != 0L) {
            if (MemoryUtil.memGetInt(pNextChain) == sType) {
                return pNextChain;
            }
            pNextChain = MemoryUtil.memGetAddress(pNextChain + Pointer.POINTER_SIZE);
        }
        return 0L;
    }

    private static void prepend(VkDeviceCreateInfo createInfo, long structAddr) {
        MemoryUtil.memPutAddress(structAddr + Pointer.POINTER_SIZE, createInfo.pNext());
        createInfo.pNext(structAddr);
    }
}
