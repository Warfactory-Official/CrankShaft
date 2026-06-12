package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.util.GpuTimerReport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

public final class VkGpuTimer {
    private static final int SLOT_COUNT = 2;
    private static final int MAX_QUERIES = 512;
    private static final int MAX_SCOPES = MAX_QUERIES / 2;
    private static final int MAX_STACK = 64;
    private static final int REPORT_TOP = 16;
    private static final long STAGE_ALL_COMMANDS = VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

    private static final Slot[] slots = new Slot[SLOT_COUNT];
    private static final int[] stackSlots = new int[MAX_STACK];
    private static final int[] stackScopeIndices = new int[MAX_STACK];
    private static final GpuTimerReport report = new GpuTimerReport(MAX_SCOPES, REPORT_TOP, "", "submit");
    private static int stackDepth;
    private static int overflowDepth;
    private static VulkanDevice queryDevice;
    private static volatile boolean enabled;
    private static boolean oneShot;

    private VkGpuTimer() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static GpuTimer.State setEnabled(boolean value) {
        if (value && !VkContext.isVulkanHost()) {
            return GpuTimer.State.UNAVAILABLE;
        }
        if (!value) {
            oneShot = false;
            collectCompleted();
            enabled = false;
            destroySlots();
            return GpuTimer.State.DISABLED;
        }
        enabled = value;
        oneShot = false;
        report.lastError = null;
        return GpuTimer.State.ENABLED;
    }

    public static GpuTimer.State captureOnce() {
        if (!VkContext.isVulkanHost()) {
            return GpuTimer.State.UNAVAILABLE;
        }
        enabled = true;
        oneShot = true;
        report.lastError = null;
        stackDepth = 0;
        overflowDepth = 0;
        return GpuTimer.State.ARMED;
    }

    static void push(VkCommandBuffer cmd, String name) {
        if (!enabled) {
            return;
        }
        Slot slot = ensureSlot();
        if (!enabled) {
            return;
        }
        int scopeIndex = -1;
        if (slot.queryCount + 2 <= MAX_QUERIES && slot.scopeCount < MAX_SCOPES) {
            int beginQuery = slot.queryCount++;
            int endQuery = slot.queryCount++;
            scopeIndex = slot.scopeCount++;
            slot.names[scopeIndex] = name;
            slot.beginQueries[scopeIndex] = beginQuery;
            slot.endQueries[scopeIndex] = endQuery;
            slot.depths[scopeIndex] = stackDepth;
            KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd, STAGE_ALL_COMMANDS, slot.queryPool, beginQuery);
        } else {
            slot.droppedScopes++;
        }

        if (stackDepth < MAX_STACK) {
            stackSlots[stackDepth] = slot.index;
            stackScopeIndices[stackDepth] = scopeIndex;
            stackDepth++;
        } else {
            slot.droppedScopes++;
            overflowDepth++;
        }
    }

    static void pop(VkCommandBuffer cmd) {
        if (!enabled) {
            return;
        }
        if (overflowDepth > 0) {
            overflowDepth--;
            return;
        }
        if (stackDepth == 0) {
            return;
        }
        stackDepth--;
        int slotIndex = stackSlots[stackDepth];
        int scopeIndex = stackScopeIndices[stackDepth];
        if (scopeIndex < 0) {
            return;
        }
        Slot slot = slots[slotIndex];
        KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd, STAGE_ALL_COMMANDS, slot.queryPool,
                slot.endQueries[scopeIndex]);
    }

    public static void collectCompleted() {
        if (!VkContext.isVulkanHost()) {
            return;
        }
        long completedEpoch = VkContext.encoder().currentSubmitIndex - 2L;
        for (Slot slot : slots) {
            if (slot != null && slot.epoch <= completedEpoch && slot.epoch != Long.MIN_VALUE && !slot.collected) {
                collect(slot);
            }
        }
    }

    public static String commandReport() {
        collectCompleted();
        StringBuilder out = new StringBuilder(1024);
        report.appendSummary(out, true, enabled, oneShot);
        return out.toString();
    }

    public static void appendDebugInfo(StringBuilder out) {
        collectCompleted();
        report.appendDebugInfo(out, enabled, oneShot);
    }

    private static Slot ensureSlot() {
        VulkanDevice currentDevice = VkContext.device();
        if (queryDevice != currentDevice) {
            queryDevice = currentDevice;
            slots[0] = null;
            slots[1] = null;
            stackDepth = 0;
            overflowDepth = 0;
        }

        long epoch = VkContext.encoder().currentSubmitIndex;
        int index = (int) (epoch & 1L);
        Slot slot = slots[index];
        if (slot == null) {
            slot = new Slot(index, createQueryPool(currentDevice));
            slots[index] = slot;
        }
        if (slot.epoch != epoch) {
            if (slot.epoch != Long.MIN_VALUE && !slot.collected) {
                collect(slot);
            }
            if (!enabled) {
                return slot;
            }
            VK12.vkResetQueryPool(VkContext.vkDevice(), slot.queryPool, 0, MAX_QUERIES);
            slot.begin(epoch);
        }
        return slot;
    }

    private static long createQueryPool(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                                                              .queryType(VK12.VK_QUERY_TYPE_TIMESTAMP)
                                                              .queryCount(MAX_QUERIES);
            LongBuffer out = stack.callocLong(1);
            int result = VK12.vkCreateQueryPool(device.vkDevice(), info, null, out);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException("Vulkan error " + result + " creating Flywheel GPU timer query pool");
            }
            long queryPool = out.get(0);
            VK12.vkResetQueryPool(device.vkDevice(), queryPool, 0, MAX_QUERIES);
            return queryPool;
        }
    }

    private static void destroySlots() {
        VulkanDevice device = queryDevice;
        if (device != null && VkContext.isVulkanHost() && VkContext.device() == device) {
            VkDevice vkDevice = device.vkDevice();
            for (Slot slot : slots) {
                if (slot != null) {
                    long queryPool = slot.queryPool;
                    VkContext.deferDestroy(() -> VK12.vkDestroyQueryPool(vkDevice, queryPool, null));
                }
            }
        }
        slots[0] = null;
        slots[1] = null;
        queryDevice = null;
        stackDepth = 0;
        overflowDepth = 0;
    }

    private static void collect(Slot slot) {
        if (slot.queryCount == 0) {
            slot.collected = true;
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer values = stack.callocLong(slot.queryCount * 2);
            int result = VK12.vkGetQueryPoolResults(VkContext.vkDevice(), slot.queryPool, 0, slot.queryCount,
                    values, 16L, VK12.VK_QUERY_RESULT_64_BIT | VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (result < 0) {
                throw new IllegalStateException("Vulkan error " + result + " reading Flywheel GPU timer query pool");
            }
            copyCompleted(slot, values);
            slot.collected = true;
            report.capturedFrames++;
            if (oneShot) {
                enabled = false;
                oneShot = false;
                destroySlots();
            }
        } catch (RuntimeException e) {
            report.lastError = e.getMessage();
            enabled = false;
            oneShot = false;
            destroySlots();
            throw e;
        }
    }

    private static void copyCompleted(Slot slot, LongBuffer values) {
        double timestampPeriod = VkContext.device().getDeviceInfo().timestampPeriod();
        report.lastDroppedScopes = slot.droppedScopes;
        report.lastIncompleteScopes = 0;
        report.lastEpoch = slot.epoch;
        report.lastCount = 1;
        long minBegin = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        for (int i = 0; i < slot.scopeCount; i++) {
            int beginQuery = slot.beginQueries[i];
            int endQuery = slot.endQueries[i];
            boolean beginReady = values.get(beginQuery * 2 + 1) != 0L;
            boolean endReady = values.get(endQuery * 2 + 1) != 0L;
            if (!beginReady || !endReady) {
                report.lastIncompleteScopes++;
                continue;
            }
            long begin = values.get(beginQuery * 2);
            long end = values.get(endQuery * 2);
            if (end < begin) {
                report.lastIncompleteScopes++;
                continue;
            }
            minBegin = Math.min(minBegin, begin);
            maxEnd = Math.max(maxEnd, end);
            if (report.lastCount < MAX_SCOPES) {
                report.lastNames[report.lastCount] = slot.names[i];
                report.lastNs[report.lastCount] = Math.round((end - begin) * timestampPeriod);
                report.lastDepths[report.lastCount] = slot.depths[i] + 1;
                report.lastCount++;
            } else {
                report.lastDroppedScopes++;
            }
        }
        if (minBegin == Long.MAX_VALUE) {
            report.lastCount = 0;
        } else {
            report.lastNames[0] = "flywheel:vk/frame";
            report.lastNs[0] = Math.round((maxEnd - minBegin) * timestampPeriod);
            report.lastDepths[0] = 0;
        }
        for (int i = report.lastCount; i < MAX_SCOPES; i++) {
            report.lastNames[i] = null;
            report.lastNs[i] = 0L;
            report.lastDepths[i] = 0;
        }
    }

    private static final class Slot {
        final int index;
        final long queryPool;
        final String[] names = new String[MAX_SCOPES];
        final int[] beginQueries = new int[MAX_SCOPES];
        final int[] endQueries = new int[MAX_SCOPES];
        final int[] depths = new int[MAX_SCOPES];
        long epoch = Long.MIN_VALUE;
        int queryCount;
        int scopeCount;
        int droppedScopes;
        boolean collected = true;

        Slot(int index, long queryPool) {
            this.index = index;
            this.queryPool = queryPool;
        }

        void begin(long epoch) {
            this.epoch = epoch;
            this.queryCount = 0;
            this.scopeCount = 0;
            this.droppedScopes = 0;
            this.collected = false;
            for (int i = 0; i < names.length; i++) {
                names[i] = null;
            }
        }
    }
}
