package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.FlwBackend;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Device-lifetime driver pipeline cache, persisted by compatible device/cache UUID. The default Vulkan cache
 * synchronization is retained so compiler workers may use it concurrently. Save/destruction happens after joining.
 */
public final class VkPipelineCaches {
    private static long cache;
    private static Path path;

    private VkPipelineCaches() {
    }

    public static void initialize() {
        RenderSystem.assertOnRenderThread();
        if (cache != 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(VkContext.vkDevice().getPhysicalDevice(), properties);
            byte[] uuid = new byte[VK10.VK_UUID_SIZE];
            properties.pipelineCacheUUID().get(uuid);
            path = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/crankshaft/vulkan-"
                    + Integer.toUnsignedString(properties.vendorID(), 16) + "-"
                    + Integer.toUnsignedString(properties.deviceID(), 16) + "-" + HexFormat.of()
                                                                                           .formatHex(uuid) + ".bin");
            byte[] data = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
            if (data.length != 0 && !compatible(data, properties.vendorID(), properties.deviceID(), uuid)) {
                FlwBackend.LOGGER.info("Discarding incompatible Vulkan pipeline cache {}", path);
                data = new byte[0];
            }
            ByteBuffer initial = data.length == 0 ? null : MemoryUtil.memAlloc(data.length).put(data).flip();
            try {
                var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(initial);
                var output = stack.callocLong(1);
                check(VK10.vkCreatePipelineCache(VkContext.vkDevice(), info, null, output), "create pipeline cache");
                cache = output.get(0);
            } finally {
                MemoryUtil.memFree(initial);
            }
            FlwBackend.LOGGER.info("Vulkan pipeline cache loaded {} bytes", data.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading Vulkan pipeline cache", e);
        }
    }

    private static boolean compatible(byte[] bytes, int vendor, int device, byte[] uuid) {
        if (bytes.length < 32) return false;
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(0) >= 32 && header.getInt(0) <= bytes.length
                && header.getInt(4) == VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE
                && header.getInt(8) == vendor && header.getInt(12) == device
                && Arrays.equals(uuid, Arrays.copyOfRange(bytes, 16, 32));
    }

    public static long handle() {
        return cache;
    }

    public static void save() {
        RenderSystem.assertOnRenderThread();
        if (cache == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.callocPointer(1);
            check(VK10.vkGetPipelineCacheData(VkContext.vkDevice(), cache, size, null), "query pipeline cache");
            ByteBuffer buffer = MemoryUtil.memAlloc(Math.toIntExact(size.get(0)));
            try {
                check(VK10.vkGetPipelineCacheData(VkContext.vkDevice(), cache, size, buffer), "read pipeline cache");
                byte[] bytes = new byte[Math.toIntExact(size.get(0))];
                buffer.get(bytes);
                Files.createDirectories(path.getParent());
                Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
                Files.write(temporary, bytes);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                FlwBackend.LOGGER.info("Vulkan pipeline cache saved {} bytes", bytes.length);
            } finally {
                MemoryUtil.memFree(buffer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Saving Vulkan pipeline cache", e);
        }
    }

    public static void shutdown() {
        if (cache == 0L) return;
        save();
        VK10.vkDestroyPipelineCache(VkContext.vkDevice(), cache, null);
        cache = 0L;
    }

    private static void check(int result, String action) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException("Vulkan error " + result + " during " + action);
    }
}
