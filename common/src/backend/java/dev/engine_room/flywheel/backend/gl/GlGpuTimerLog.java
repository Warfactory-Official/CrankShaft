package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.FlwBackend;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import net.minecraft.util.Util;

import java.util.Locale;

/**
 * {@code -Dcrankshaft.gpuTimerLog=<ms>} (OpenGL): the GPU timer runs from the first level render, one frame per
 * {@code GameRenderer.renderLevel} (scope {@code frame/level}) rather than per engine frame, and logs each scope's
 * per-frame mean over every {@code <ms>} of wall time. Engine-off frames are timed too.
 */
public final class GlGpuTimerLog {
    public static final long WINDOW_MS = Long.getLong("crankshaft.gpuTimerLog", 0L);
    public static final boolean ENABLED = WINDOW_MS > 0;
    private static final Object2LongLinkedOpenHashMap<String> SUMS = new Object2LongLinkedOpenHashMap<>();
    private static long windowStart;
    private static long frames;
    private static long captured;

    private GlGpuTimerLog() {
    }

    public static void beginLevel() {
        if (!GlGpuTimer.isEnabled()) {
            GlGpuTimer.setEnabled(true);
        }
        GlGpuTimer.rotate();
        var report = GlGpuTimer.report();
        if (report.capturedFrames != captured) {
            captured = report.capturedFrames;
            frames++;
            for (int i = 1; i < report.lastCount; i++) {
                SUMS.addTo(report.lastNames[i], report.lastNs[i]);
            }
        }
        long now = Util.getMillis();
        if (windowStart == 0L) {
            windowStart = now;
        } else if (now - windowStart >= WINDOW_MS && frames > 0) {
            var line = new StringBuilder("[gpuTimerLog] frames=").append(frames);
            SUMS.object2LongEntrySet().forEach(e -> line.append(' ').append(e.getKey()).append('=')
                    .append(String.format(Locale.ROOT, "%.3f", e.getLongValue() / 1e6 / frames)));
            FlwBackend.LOGGER.info(line);
            SUMS.clear();
            frames = 0;
            windowStart = now;
        }
        GlCompat.pushDebugGroup("frame/level");
    }

    public static void endLevel() {
        GlCompat.popDebugGroup();
    }
}
