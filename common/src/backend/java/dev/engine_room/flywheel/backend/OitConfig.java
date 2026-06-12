package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * Runtime OIT configuration: the user's chosen path + a per-path translucent-layer budget. Driven by
 * {@code /flywheel oit} and (later) the Sodium options GUI; read by the VK backend each frame. The single source
 * of truth both UIs mutate.
 *
 * <p>Each path's layer element defaults to {@code 0} = "follow the preset"; a non-zero value is a manual override
 * (clamped to {@code [1, MAX_LAYERS]}). K is a runtime uniform ({@code _flw_mlabK}), so a change applies next frame
 * with no recompile. For k-buffer/MLAB it is the sample budget; for the A-buffer it is the resolve's nearest-N cap.
 *
 * <p>Persisted to the loader config file: {@link #setSaver} registers the loader's writer (run by every mutator)
 * and {@link #loadState} seeds from disk at startup -- wired by both {@code FabricFlwConfig} and
 * {@code NeoForgeFlwConfig}.
 */
public final class OitConfig {
    public static final int MAX_LAYERS = 32;
    // Path.ordinal-indexed. AUTO/WAVELET slots are inert (no layer budget).
    private static final int[] PRESET = {0, 0, 4, 8, 16};
    // 0 = follow preset; else a manual override in [1, MAX_LAYERS]. Path.ordinal-indexed.
    private static final int[] layers = new int[Path.values().length];
    private static volatile Path path = Path.AUTO;
    // Weather accuracy model, consistent across ALL OIT chains. true = full OIT: rain/snow are geometry
    // producers whose fragments depth-sort exactly against every other translucent. false = resolved layer:
    // rain/snow render ONCE with vanilla's WEATHER pipeline and enter the chain as one sample per pixel
    // (vanilla-fabulous semantics; far cheaper in rain -- the exact insert cost ~1ms/frame from fullscreen
    // quad overdraw). The clouds/item/particle layers are single resolved samples under BOTH settings.
    private static volatile boolean exactFabulous = false;
    // Persistence seam: the loader registers a writer that flushes the current state to its config file. Every
    // mutator (command / future GUI) triggers it; loadState() seeds from disk WITHOUT re-saving.
    private static Runnable saver = () -> {
    };

    private OitConfig() {
    }

    public static void setSaver(Runnable r) {
        saver = r;
    }

    /**
     * Seed the runtime state from persisted values (no save is triggered). {@code layers} entries: 0 = preset.
     */
    public static void loadState(Path p, int kbuffer, int mlab, int abuffer, boolean exact) {
        path = p;
        layers[Path.KBUFFER.ordinal()] = clampLayers(kbuffer);
        layers[Path.MLAB.ordinal()] = clampLayers(mlab);
        layers[Path.ABUFFER.ordinal()] = clampLayers(abuffer);
        exactFabulous = exact;
    }

    public static boolean exactFabulous() {
        return exactFabulous;
    }

    public static void setExactFabulous(boolean v) {
        exactFabulous = v;
        saver.run();
    }

    private static int clampLayers(int v) {
        return v <= 0 ? 0 : Math.min(MAX_LAYERS, v);
    }

    public static Path path() {
        return path;
    }

    public static void setPath(Path p) {
        path = p;
        saver.run();
    }

    // The active host's fragment-shader-interlock capability: VK negotiates it at device creation, GL exposes the ARB
    // extension. Only MLAB needs it (k-buffer/A-buffer ride SSBO atomics, present on any insert-capable backend).
    private static boolean hostSupportsInterlock() {
        return VkContext.isVulkanHost()
                ? VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED
                : GlCompat.SUPPORTS_FRAGMENT_INTERLOCK;
    }

    /**
     * GL-host only: the wavelet coefficients live in ONE layered RGBA16F array (upstream's storage shape),
     * sampled raw as {@code sampler2DArray} at unit 11 and ATTACHED through per-layer texture views (Mojang
     * render passes cannot attach array layers). VK keeps 4 layer views of one image; legacy GL without
     * ARB_texture_view keeps the 4-discrete-texture split. Session-stable -- compiled into the shaders.
     */
    public static boolean coefficientArray() {
        return !VkContext.isVulkanHost() && GlCompat.SUPPORTS_TEXTURE_VIEW;
    }

    /**
     * The concrete Path this frame given device capability -- AUTO picks MLAB on interlock hardware else wavelet,
     * and an explicitly-chosen MLAB falls back to wavelet where interlock is absent.
     */
    public static Path resolvePath() {
        boolean interlock = hostSupportsInterlock();
        // AUTO = best available on BOTH hosts: the single-geometry-pass insert MLAB where interlock is present (VK or
        // GL), else the multi-pass wavelet chain. An explicit mode is honored either way (MLAB falls back below).
        Path p = path == Path.AUTO ? (interlock ? Path.MLAB : Path.WAVELET) : path;
        if (p == Path.MLAB && !interlock) {
            p = Path.WAVELET;
        }
        return p;
    }

    /**
     * The resolved insert mode this frame, or {@code null} for the wavelet chain.
     */
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

    /**
     * The effective layer budget for a mode: the manual override, or the preset when unset (0).
     */
    public static int layersFor(OitInsertMode mode) {
        Path p = pathOf(mode);
        int v = layers[p.ordinal()];
        return v == 0 ? PRESET[p.ordinal()] : v;
    }

    /**
     * Set the layer budget for a path ({@code 0} = follow preset), clamped to {@code [1, MAX_LAYERS]}.
     */
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

    /**
     * Set the layer budget of the currently-effective insert mode; returns it, or {@code null} if wavelet is active.
     */
    public static @Nullable OitInsertMode setLayersForEffective(int n) {
        OitInsertMode m = resolveInsertMode();
        if (m != null) {
            setLayers(pathOf(m), n);
        }
        return m;
    }

    /**
     * One-line status for the command feedback.
     */
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
        sb.append(" | weather=").append(exactFabulous ? "exact" : "layered");
        return sb.toString();
    }

    /**
     * The user-selectable OIT path. {@link #AUTO} resolves to the best available for the device.
     */
    public enum Path {
        AUTO, WAVELET, KBUFFER, MLAB, ABUFFER
    }
}
