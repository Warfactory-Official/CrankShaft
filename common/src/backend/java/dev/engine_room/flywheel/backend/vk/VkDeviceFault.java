package dev.engine_room.flywheel.backend.vk;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorHeap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

public final class VkDeviceFault {
    private VkDeviceFault() {
    }

    public static void dump() {
        if (!VkCaps.DEVICE_FAULT_NEGOTIATED) {
            FlwBackend.LOGGER.error("[vk] device lost; VK_EXT_device_fault not negotiated, no structured fault report");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceFaultCountsEXT counts = VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
            int result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(VkContext.vkDevice(), counts, null);
            if (result < 0) {
                FlwBackend.LOGGER.error("[vk] vkGetDeviceFaultInfoEXT (count) failed: {}", result);
                return;
            }
            int addressCount = (int) Math.min(counts.addressInfoCount(), 64);
            int vendorCount = (int) Math.min(counts.vendorInfoCount(), 64);
            long vendorBinarySize = counts.vendorBinarySize();
            // LWJGL types pAddressInfos/pVendorInfos as single-struct pointers and exposes the output pointers getter-only; point them at stack arrays via the member offsets.
            VkDeviceFaultAddressInfoEXT.Buffer addresses = VkDeviceFaultAddressInfoEXT.calloc(Math.max(1, addressCount),
                    stack);
            VkDeviceFaultVendorInfoEXT.Buffer vendors = VkDeviceFaultVendorInfoEXT.calloc(Math.max(1, vendorCount),
                    stack);
            VkDeviceFaultInfoEXT info = VkDeviceFaultInfoEXT.calloc(stack).sType$Default();
            if (addressCount > 0) {
                MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PADDRESSINFOS, addresses.address());
            }
            if (vendorCount > 0) {
                MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PVENDORINFOS, vendors.address());
            }
            counts.addressInfoCount(addressCount);
            counts.vendorInfoCount(vendorCount);
            counts.vendorBinarySize(0);
            result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(VkContext.vkDevice(), counts, info);
            if (result < 0) {
                FlwBackend.LOGGER.error("[vk] vkGetDeviceFaultInfoEXT (fill) failed: {}", result);
                return;
            }
            FlwBackend.LOGGER.error("[vk] device fault: \"{}\" ({} address range(s), {} vendor info(s))",
                    info.descriptionString(), addressCount, vendorCount);
            if (VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED) {
                FlwBackend.LOGGER.error("[vk]   db binds: recorded={} elided={}",
                        VkDescriptorHeap.bindsRecorded(), VkDescriptorHeap.bindsElided());
            }
            for (int i = 0; i < addressCount; i++) {
                VkDeviceFaultAddressInfoEXT a = addresses.get(i);
                long precision = Math.max(1L, a.addressPrecision());
                long lo = a.reportedAddress() & ~(precision - 1);
                long hi = a.reportedAddress() | (precision - 1);
                FlwBackend.LOGGER.error("[vk]   fault address: type={} range=[0x{}, 0x{}]",
                        addressTypeName(a.addressType()), Long.toHexString(lo), Long.toHexString(hi));
            }
            for (int i = 0; i < vendorCount; i++) {
                VkDeviceFaultVendorInfoEXT v = vendors.get(i);
                FlwBackend.LOGGER.error("[vk]   vendor fault: \"{}\" code=0x{} data=0x{}",
                        v.descriptionString(), Long.toHexString(v.vendorFaultCode()),
                        Long.toHexString(v.vendorFaultData()));
            }
            if (VkCaps.DEVICE_FAULT_VENDOR_BINARY_NEGOTIATED) {
                dumpVendorBinary(vendorBinarySize);
            }
        } catch (Throwable t) {
            FlwBackend.LOGGER.error("[vk] device-fault dump failed", t);
        }
    }

    private static void dumpVendorBinary(long size) {
        if (size <= 0 || size > 512L * 1024 * 1024) {
            FlwBackend.LOGGER.error("[vk] no vendor crash-dump blob (size={})", size);
            return;
        }
        java.nio.ByteBuffer blob = MemoryUtil.memAlloc((int) size);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceFaultCountsEXT counts = VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
            counts.vendorBinarySize(size);
            VkDeviceFaultInfoEXT info = VkDeviceFaultInfoEXT.calloc(stack).sType$Default();
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PVENDORBINARYDATA,
                    MemoryUtil.memAddress(blob));
            int result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(VkContext.vkDevice(), counts, info);
            if (result < 0) {
                FlwBackend.LOGGER.error("[vk] vendor crash-dump retrieval failed: {}", result);
                return;
            }
            byte[] bytes = new byte[(int) size];
            blob.get(bytes);
            java.nio.file.Path out = java.nio.file.Path.of("device-fault-" + System.currentTimeMillis() + ".nv-gpudmp");
            java.nio.file.Files.write(out, bytes);
            FlwBackend.LOGGER.error("[vk] vendor crash dump written: {} ({} bytes)", out.toAbsolutePath(), size);
        } catch (Throwable t) {
            FlwBackend.LOGGER.error("[vk] vendor crash-dump write failed", t);
        } finally {
            MemoryUtil.memFree(blob);
        }
    }

    private static String addressTypeName(int type) {
        return switch (type) {
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_NONE_EXT -> "NONE";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT -> "READ_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT -> "WRITE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_EXECUTE_INVALID_EXT -> "EXECUTE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_UNKNOWN_EXT -> "IP_UNKNOWN";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_INVALID_EXT -> "IP_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_FAULT_EXT -> "IP_FAULT";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
