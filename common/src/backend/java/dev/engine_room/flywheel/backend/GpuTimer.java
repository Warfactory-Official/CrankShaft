package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.gl.GlGpuTimer;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.VkGpuTimer;

/**
 * Backend-neutral front for the UE-style GPU timers: routes to {@link VkGpuTimer} on a Vulkan host and
 * {@link GlGpuTimer} on an OpenGL host, so one {@code /flywheel debug gpuTimer} command + the F3 overlay drive
 * whichever backend is live. Both timers key off the pass labels ({@code VkContext.pushLabel} / {@code
 * GlCompat.pushDebugGroup}) that wrap every GPU-work seam.
 */
public final class GpuTimer {
    private GpuTimer() {
    }

    private static boolean vulkan() {
        return VkContext.isVulkanHost();
    }

    public static State setEnabled(boolean value) {
        return vulkan() ? VkGpuTimer.setEnabled(value) : GlGpuTimer.setEnabled(value);
    }

    public static State captureOnce() {
        return vulkan() ? VkGpuTimer.captureOnce() : GlGpuTimer.captureOnce();
    }

    public static String commandReport() {
        return vulkan() ? VkGpuTimer.commandReport() : GlGpuTimer.commandReport();
    }

    public static void appendDebugInfo(StringBuilder out) {
        if (vulkan()) {
            VkGpuTimer.appendDebugInfo(out);
        } else {
            GlGpuTimer.appendDebugInfo(out);
        }
    }

    /**
     * Once-per-frame ring rotation for the GL timer (Vulkan self-rotates on its submit index).
     */
    public static void beginFrame() {
        if (!vulkan()) {
            GlGpuTimer.beginFrame();
        }
    }

    public enum State {
        ENABLED,
        DISABLED,
        ARMED,
        UNAVAILABLE
    }
}
