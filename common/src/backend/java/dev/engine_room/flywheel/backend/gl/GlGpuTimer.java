package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.util.GpuTimerReport;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * OpenGL timestamp-query stream keyed by {@link GlCompat#pushDebugGroup}, in a ring of per-frame query sets
 * so a completed frame reads back without stalling; GL is in-order, so the ring rotates once per frame.
 */
public final class GlGpuTimer {
    private static final int SLOT_COUNT = 4;
    private static final int MAX_QUERIES = 512;
    private static final int MAX_SCOPES = MAX_QUERIES / 2;
    private static final int MAX_STACK = 64;
    private static final int REPORT_TOP = 16;

    private static final Slot[] slots = new Slot[SLOT_COUNT];
    private static final int[] stackScopeIndices = new int[MAX_STACK];
    private static final GpuTimerReport report = new GpuTimerReport(MAX_SCOPES, REPORT_TOP, " (GL)", "frame");
    private static int writeSlot;
    private static long frameSeq;
    private static int stackDepth;
    private static int overflowDepth;
    private static volatile boolean enabled;
    private static boolean oneShot;

    private GlGpuTimer() {
    }

    private static boolean available() {
        return GlCompat.SUPPORTS_TIMER_QUERY;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static GpuTimer.State setEnabled(boolean value) {
        if (value && !available()) {
            return GpuTimer.State.UNAVAILABLE;
        }
        if (!value) {
            oneShot = false;
            enabled = false;
            return GpuTimer.State.DISABLED;
        }
        enabled = true;
        oneShot = false;
        return GpuTimer.State.ENABLED;
    }

    public static GpuTimer.State captureOnce() {
        if (!available()) {
            return GpuTimer.State.UNAVAILABLE;
        }
        enabled = true;
        oneShot = true;
        return GpuTimer.State.ARMED;
    }

    static void push(String name) {
        if (!enabled) {
            return;
        }
        Slot slot = ensureWriteSlot();
        int scopeIndex = -1;
        if (slot.queryCount + 2 <= MAX_QUERIES && slot.scopeCount < MAX_SCOPES) {
            int beginQuery = slot.queryCount++;
            int endQuery = slot.queryCount++;
            scopeIndex = slot.scopeCount++;
            slot.names[scopeIndex] = name;
            slot.beginQueries[scopeIndex] = beginQuery;
            slot.endQueries[scopeIndex] = endQuery;
            slot.depths[scopeIndex] = stackDepth;
            GL33C.glQueryCounter(slot.queries[beginQuery], GL33C.GL_TIMESTAMP);
        } else {
            slot.droppedScopes++;
        }

        if (stackDepth < MAX_STACK) {
            stackScopeIndices[stackDepth] = scopeIndex;
            stackDepth++;
        } else {
            slot.droppedScopes++;
            overflowDepth++;
        }
    }

    static void pop() {
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
        int scopeIndex = stackScopeIndices[stackDepth];
        if (scopeIndex < 0) {
            return;
        }
        Slot slot = slots[writeSlot];
        GL33C.glQueryCounter(slot.queries[slot.endQueries[scopeIndex]], GL33C.GL_TIMESTAMP);
    }

    // The current write slot, lazily opened; terrain's push can precede the frame's beginFrame.
    private static Slot ensureWriteSlot() {
        Slot slot = slots[writeSlot];
        if (slot == null) {
            slot = slots[writeSlot] = new Slot();
            slot.begin();
        }
        return slot;
    }

    public static void beginFrame() {
        if (!available()) {
            return;
        }
        // Only rotate while timing is on (off by default -> zero GL objects allocated); a disabled
        // timer still drains any capture left pending from a just-finished one-shot / disable.
        if (enabled) {
            Slot finished = slots[writeSlot];
            if (finished != null && finished.scopeCount > 0) {
                finished.pending = true;
                finished.collected = false;
            }

            writeSlot = (writeSlot + 1) % SLOT_COUNT;
            Slot slot = slots[writeSlot];
            if (slot == null) {
                slot = slots[writeSlot] = new Slot();
            }
            slot.begin();
            stackDepth = 0;
            overflowDepth = 0;
        }

        collectFreshest();
    }

    private static void collectFreshest() {
        Slot best = null;
        for (Slot slot : slots) {
            if (slot == null || !slot.pending || slot.collected || slot.scopeCount == 0) {
                continue;
            }
            if (best == null || slot.frameId > best.frameId) {
                best = slot;
            }
        }
        if (best != null && best.resultsAvailable()) {
            copyCompleted(best);
            best.collected = true;
            report.capturedFrames++;
            if (oneShot) {
                enabled = false;
                oneShot = false;
            }
        }
    }

    public static String commandReport() {
        collectFreshest();
        StringBuilder out = new StringBuilder(1024);
        report.appendSummary(out, true, enabled, oneShot);
        return out.toString();
    }

    public static void appendDebugInfo(StringBuilder out) {
        collectFreshest();
        report.appendDebugInfo(out, enabled, oneShot);
    }

    private static void copyCompleted(Slot slot) {
        report.lastDroppedScopes = slot.droppedScopes;
        report.lastIncompleteScopes = 0;
        report.lastEpoch = slot.frameId;
        report.lastCount = 1;
        long minBegin = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        for (int i = 0; i < slot.scopeCount; i++) {
            long begin = slot.readQuery(slot.beginQueries[i]);
            long end = slot.readQuery(slot.endQueries[i]);
            if (end < begin) {
                report.lastIncompleteScopes++;
                continue;
            }
            minBegin = Math.min(minBegin, begin);
            maxEnd = Math.max(maxEnd, end);
            if (report.lastCount < MAX_SCOPES) {
                report.lastNames[report.lastCount] = slot.names[i];
                report.lastNs[report.lastCount] = end - begin;
                report.lastDepths[report.lastCount] = slot.depths[i] + 1;
                report.lastCount++;
            } else {
                report.lastDroppedScopes++;
            }
        }
        if (minBegin == Long.MAX_VALUE) {
            report.lastCount = 0;
        } else {
            report.lastNames[0] = "flywheel:gl/frame";
            report.lastNs[0] = maxEnd - minBegin;
            report.lastDepths[0] = 0;
        }
        for (int i = report.lastCount; i < MAX_SCOPES; i++) {
            report.lastNames[i] = null;
            report.lastNs[i] = 0L;
            report.lastDepths[i] = 0;
        }
    }

    private static final class Slot {
        final int[] queries = new int[MAX_QUERIES];
        final String[] names = new String[MAX_SCOPES];
        final int[] beginQueries = new int[MAX_SCOPES];
        final int[] endQueries = new int[MAX_SCOPES];
        final int[] depths = new int[MAX_SCOPES];
        long frameId = Long.MIN_VALUE;
        int queryCount;
        int scopeCount;
        int droppedScopes;
        boolean pending;
        boolean collected = true;

        Slot() {
            GL15C.glGenQueries(queries);
        }

        void begin() {
            frameId = frameSeq++;
            queryCount = 0;
            scopeCount = 0;
            droppedScopes = 0;
            pending = false;
            collected = false;
        }

        boolean resultsAvailable() {
            if (queryCount == 0) {
                return true;
            }
            return GL15C.glGetQueryObjecti(queries[queryCount - 1], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0;
        }

        long readQuery(int index) {
            long[] out = new long[1];
            GL33C.glGetQueryObjectui64v(queries[index], GL15C.GL_QUERY_RESULT, out);
            return out[0];
        }
    }
}
