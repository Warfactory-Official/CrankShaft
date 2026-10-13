package dev.engine_room.flywheel.backend;

#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
#endif
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.engine.indirect.GlInsertOitChain;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

/** Loader persistence: register writer via {@link #setSaver}; seed startup values via {@link #loadState}. */
public final class OitConfig {
    public static final int MAX_LAYERS = 32;
    private static final int[] PRESET = {0, 0, 4, 8, 16};
    private static final int[] layers = new int[Path.values().length];
    private static volatile Path path = Path.AUTO;
    #if MC < 26.3
    private static volatile boolean exactFabulous = false;
    #endif
    private static Runnable saver = () -> {
    };

    private OitConfig() {
    }

    public static void setSaver(Runnable r) {
        saver = r;
    }

    public static void loadState(Path p, int kbuffer, int mlab, int abuffer #if MC < 26.3 , boolean exact #endif) {
        #if MC >= 26.3
        NativeOitWarmup.configure(() -> applyState(p, kbuffer, mlab, abuffer));
        #else
        applyState(p, kbuffer, mlab, abuffer);
        exactFabulous = exact;
        #endif
    }

    private static void applyState(Path p, int kbuffer, int mlab, int abuffer) {
        path = p;
        layers[Path.KBUFFER.ordinal()] = clampLayers(kbuffer);
        layers[Path.MLAB.ordinal()] = clampLayers(mlab);
        layers[Path.ABUFFER.ordinal()] = clampLayers(abuffer);
    }

    #if MC < 26.3
    public static boolean exactFabulous() {
        return exactFabulous;
    }

    public static void setExactFabulous(boolean v) {
        exactFabulous = v;
        saver.run();
    }
    #endif

    private static int clampLayers(int v) {
        return v <= 0 ? 0 : Math.min(MAX_LAYERS, v);
    }

    public static Path path() {
        return path;
    }

    public static void setPath(Path p) {
        #if MC >= 26.3
        NativeOitWarmup.configure(() -> {
            path = p;
            saver.run();
        });
        #else
        path = p;
        saver.run();
        #endif
    }

    private static boolean hostSupportsInterlock() {
        return VkContext.isVulkanHost()
                ? VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED
                : GlCompat.SUPPORTS_FRAGMENT_INTERLOCK;
    }

    public static boolean coefficientArray() {
        return !VkContext.isVulkanHost() && GlCompat.SUPPORTS_TEXTURE_VIEW;
    }

    private static boolean hostSupportsInsert() {
        return VkContext.isVulkanHost() ? VkCaps.FRAGMENT_STORES_AND_ATOMICS_NEGOTIATED
                : GlInsertOitChain.isSupported();
    }

    public static boolean supportsInsertMode(OitInsertMode mode) {
        if (!hostSupportsInsert() || !ProgramAvailability.allows(ProgramAvailability.insert(mode))) return false;
        if (mode.needsInterlock() && !hostSupportsInterlock()) return false;
        return !VkContext.isVulkanHost() || mode != OitInsertMode.ABUFFER
                || VkCaps.MAX_PER_STAGE_DESCRIPTOR_STORAGE_BUFFERS >= 5;
    }

    public static Path resolvePath() {
        boolean interlock = hostSupportsInterlock();
        boolean insert = hostSupportsInsert();
        Path p = path == Path.AUTO ? (interlock && insert ? Path.MLAB : Path.WAVELET) : path;
        if (p == Path.MLAB && (!interlock || !supportsInsertMode(OitInsertMode.MLAB))
                || p == Path.KBUFFER && !supportsInsertMode(OitInsertMode.KBUFFER)
                || p == Path.ABUFFER && !supportsInsertMode(OitInsertMode.ABUFFER)
                || p != Path.WAVELET && !insert) {
            p = Path.WAVELET;
        }
        return p;
    }

    public static @Nullable OitInsertMode resolveInsertMode() {
        return switch (resolvePath()) {
            case KBUFFER -> OitInsertMode.KBUFFER;
            case MLAB -> OitInsertMode.MLAB;
            case ABUFFER -> OitInsertMode.ABUFFER;
            case AUTO, WAVELET -> null;
        };
    }

    private static Path pathOf(OitInsertMode mode) {
        return switch (mode) {
            case KBUFFER -> Path.KBUFFER;
            case MLAB -> Path.MLAB;
            case ABUFFER -> Path.ABUFFER;
        };
    }

    public static int layersFor(OitInsertMode mode) {
        Path p = pathOf(mode);
        int v = layers[p.ordinal()];
        return v == 0 ? PRESET[p.ordinal()] : v;
    }

    public static void setLayers(Path p, int v) {
        layers[p.ordinal()] = clampLayers(v);
        saver.run();
    }

    public static int rawLayers(Path p) {
        return layers[p.ordinal()];
    }

    public static int preset(Path p) {
        return PRESET[p.ordinal()];
    }

    public static void resetLayers() {
        Arrays.fill(layers, 0);
        saver.run();
    }

    public static @Nullable OitInsertMode setLayersForEffective(int n) {
        OitInsertMode m = resolveInsertMode();
        if (m != null) {
            setLayers(pathOf(m), n);
        }
        return m;
    }

    public static String status() {
        StringBuilder sb = new StringBuilder("OIT: path=").append(path.name().toLowerCase(Locale.ROOT));
        Path resolved = resolvePath();
        if (resolved != path) {
            sb.append(" (-> ").append(resolved.name().toLowerCase(Locale.ROOT)).append(')');
        }
        sb.append(" | layers:");
        for (OitInsertMode m : OitInsertMode.values()) {
            int raw = layers[pathOf(m).ordinal()];
            sb.append(' ').append(m.name().toLowerCase(Locale.ROOT)).append('=').append(layersFor(m));
            if (raw == 0) {
                sb.append("(preset)");
            }
        }
        #if MC < 26.3
        sb.append(" | weather=").append(exactFabulous ? "exact" : "layered");
        #endif
        return sb.toString();
    }

    public enum Path {
        AUTO, WAVELET, KBUFFER, MLAB, ABUFFER
    }
}
