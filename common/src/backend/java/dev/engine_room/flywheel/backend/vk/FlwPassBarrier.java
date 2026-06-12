package dev.engine_room.flywheel.backend.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * Replaces Mojang's nuclear ALL_COMMANDS submit barrier with the precise stage/access masks the owning flywheel pass
 * declares via {@link #expectFramebufferSample}; passes that declare nothing get the scoped {@link #emitDefault}.
 */
public final class FlwPassBarrier {
    private static final long STAGE_COLOR_OUTPUT = 0x400L;
    private static final long STAGE_EARLY_FRAGMENT = 0x100L;
    private static final long STAGE_LATE_FRAGMENT = 0x200L;
    private static final long STAGE_FRAGMENT_SHADER = 0x80L;
    private static final long STAGE_COMPUTE_SHADER = 0x800L;
    private static final long STAGE_ALL_TRANSFER = 0x1000L;
    private static final long STAGE_ALL_GRAPHICS = 0x8000L;
    private static final long STAGE_TASK_SHADER = 0x80000L;
    private static final long STAGE_MESH_SHADER = 0x100000L;
    private static final long ACCESS_SHADER_READ = 0x20L;
    private static final long ACCESS_COLOR_READ = 0x80L;
    private static final long ACCESS_COLOR_WRITE = 0x100L;
    private static final long ACCESS_DEPTH_READ = 0x200L;
    private static final long ACCESS_DEPTH_WRITE = 0x400L;
    private static final long ACCESS_TRANSFER_WRITE = 0x1000L;
    private static final long ACCESS_MEMORY_READ = 0x8000L;
    private static final long ACCESS_MEMORY_WRITE = 0x10000L;

    private static boolean pending;
    private static long srcStage;
    private static long srcAccess;
    private static long dstStage;
    private static long dstAccess;

    private FlwPassBarrier() {
    }

    public static void expect(long srcStage, long srcAccess, long dstStage, long dstAccess) {
        pending = true;
        FlwPassBarrier.srcStage = srcStage;
        FlwPassBarrier.srcAccess = srcAccess;
        FlwPassBarrier.dstStage = dstStage;
        FlwPassBarrier.dstAccess = dstAccess;
    }

    public static void expectFramebufferSample() {
        expect(STAGE_COLOR_OUTPUT | STAGE_LATE_FRAGMENT,
                ACCESS_COLOR_WRITE | ACCESS_DEPTH_WRITE,
                STAGE_FRAGMENT_SHADER | STAGE_EARLY_FRAGMENT | STAGE_COLOR_OUTPUT,
                ACCESS_SHADER_READ | ACCESS_DEPTH_READ | ACCESS_COLOR_READ | ACCESS_COLOR_WRITE);
    }

    public static void expectFramebufferProducer() {
        expect(STAGE_COLOR_OUTPUT | STAGE_LATE_FRAGMENT,
                ACCESS_COLOR_WRITE | ACCESS_DEPTH_WRITE,
                STAGE_COLOR_OUTPUT | STAGE_EARLY_FRAGMENT | STAGE_LATE_FRAGMENT | STAGE_FRAGMENT_SHADER,
                ACCESS_COLOR_READ | ACCESS_COLOR_WRITE | ACCESS_DEPTH_READ | ACCESS_DEPTH_WRITE | ACCESS_SHADER_READ);
    }

    public static void clear() {
        pending = false;
    }

    public static boolean emitIfPending(VkCommandBuffer cmd, MemoryStack stack) {
        if (!pending) {
            return false;
        }
        pending = false;
        barrier(cmd, stack, srcStage, srcAccess, dstStage, dstAccess);
        return true;
    }

    public static void emitDefault(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack,
                STAGE_ALL_GRAPHICS, ACCESS_MEMORY_WRITE,
                STAGE_COLOR_OUTPUT | STAGE_EARLY_FRAGMENT | STAGE_LATE_FRAGMENT | STAGE_FRAGMENT_SHADER | STAGE_ALL_TRANSFER,
                ACCESS_MEMORY_READ | ACCESS_MEMORY_WRITE);
    }

    public static void emitTransferPublish(VkCommandBuffer cmd, MemoryStack stack) {
        long meshStages = VkCaps.MESH_SHADER_NEGOTIATED ? STAGE_TASK_SHADER | STAGE_MESH_SHADER : 0L;
        barrier(cmd, stack,
                STAGE_ALL_TRANSFER, ACCESS_TRANSFER_WRITE,
                STAGE_ALL_GRAPHICS | STAGE_ALL_TRANSFER | meshStages, ACCESS_MEMORY_READ | ACCESS_MEMORY_WRITE);
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess, long dstStage,
                                long dstAccess) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                                                          .srcStageMask(srcStage).srcAccessMask(srcAccess)
                                                          .dstStageMask(dstStage).dstAccessMask(dstAccess);
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dependency);
    }
}
