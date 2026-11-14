package dev.engine_room.flywheel.backend.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

public final class GpuTimerReport {
    public final String[] lastNames;
    public final long[] lastNs;
    public final int[] lastDepths;
    private final int reportTop;
    private final String labelSuffix;
    private final String epochLabel;
    private final int[] sortScratch;

    public int lastCount;
    public long lastEpoch = Long.MIN_VALUE;
    public int lastDroppedScopes;
    public int lastIncompleteScopes;
    public long capturedFrames;
    public String lastError;

    public GpuTimerReport(int maxScopes, int reportTop, String labelSuffix, String epochLabel) {
        this.reportTop = reportTop;
        this.labelSuffix = labelSuffix;
        this.epochLabel = epochLabel;
        this.lastNames = new String[maxScopes];
        this.lastNs = new long[maxScopes];
        this.lastDepths = new int[maxScopes];
        this.sortScratch = new int[maxScopes];
    }

    private static StringBuilder appendLine(StringBuilder out, String label) {
        out.append("\n- ").append(label);
        return out;
    }

    public static String formatNs(long ns) {
        if (ns >= 100_000L) {
            return String.format(Locale.ROOT, "%.3f ms", ns / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%.3f us", ns / 1_000.0);
    }

    public MutableComponent commandReport(boolean enabled, boolean oneShot) {
        String prefix = "command.flywheel.debug.gpu_timer.report.";
        var state = Component.translatable(prefix + "state." + (enabled ? oneShot ? "armed" : "on" : "off"));
        var out = Component.translatable(prefix + "state", labelSuffix, state);
        if (lastError != null) {
            out.append(Component.translatable(prefix + "error", lastError));
        }
        if (lastEpoch == Long.MIN_VALUE) {
            return out.append(Component.translatable(prefix + "waiting"));
        }
        out.append(Component.translatable(prefix + "completed_" + epochLabel, lastEpoch, capturedFrames, lastCount));
        if (lastDroppedScopes != 0 || lastIncompleteScopes != 0) {
            out.append(Component.translatable(prefix + "dropped", lastDroppedScopes, lastIncompleteScopes));
        }
        int count = sortTop(reportTop);
        if (count == 0) return out;
        out.append(Component.translatable(prefix + "top"));
        for (int i = 0; i < count; i++) {
            int index = sortScratch[i];
            out.append("\n- " + lastNames[index] + ": " + formatNs(lastNs[index]));
        }
        out.append(Component.translatable(prefix + "recorded"));
        for (int i = 0; i < lastCount; i++) {
            out.append("\n" + "  ".repeat(lastDepths[i]) + "- " + lastNames[i] + ": " + formatNs(lastNs[i]));
        }
        return out;
    }

    public void appendDebugInfo(StringBuilder out, boolean enabled, boolean oneShot) {
        out.append("\n## GPU Timer").append(labelSuffix);
        appendLine(out, "State: ").append(enabled ? oneShot ? "armed" : "on" : "off");
        if (lastError != null) {
            appendLine(out, "Last error: ").append(lastError);
        }
        if (lastEpoch == Long.MIN_VALUE) {
            appendLine(out, "No completed frame captured");
            return;
        }
        appendLine(out, "Last completed ").append(epochLabel).append(": ").append(lastEpoch);
        appendLine(out, "Captured scopes: ").append(lastCount);
        if (lastDroppedScopes != 0 || lastIncompleteScopes != 0) {
            appendLine(out, "Dropped/incomplete scopes: ").append(lastDroppedScopes).append(" / ")
                                                          .append(lastIncompleteScopes);
        }
        appendTop(out, Math.min(8, reportTop));
    }

    private int sortTop(int limit) {
        if (lastCount == 0) {
            return 0;
        }
        int count = Math.min(lastCount, limit);
        for (int i = 0; i < lastCount; i++) {
            sortScratch[i] = i;
        }
        for (int i = 1; i < lastCount; i++) {
            int v = sortScratch[i];
            long t = lastNs[v];
            int j = i - 1;
            while (j >= 0 && lastNs[sortScratch[j]] < t) {
                sortScratch[j + 1] = sortScratch[j];
                j--;
            }
            sortScratch[j + 1] = v;
        }
        return count;
    }

    private void appendTop(StringBuilder out, int limit) {
        int count = sortTop(limit);
        if (count == 0) return;
        out.append("\nTop scopes:");
        for (int i = 0; i < count; i++) {
            int idx = sortScratch[i];
            out.append('\n').append("- ").append(lastNames[idx]).append(": ").append(formatNs(lastNs[idx]));
        }
    }
}
