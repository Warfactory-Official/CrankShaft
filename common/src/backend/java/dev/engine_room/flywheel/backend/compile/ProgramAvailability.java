package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.BackendUnavailableException;

// Render-thread rejection after warm-worker join; resource apply resets availability.
public final class ProgramAvailability {
    public enum Feature {
        INSTANCE_CULL,
        DRAW_INDEX,
        HIZ_MULTI,
        HIZ_SINGLE,
        INSERT_MLAB,
        INSERT_KBUFFER,
        INSERT_ABUFFER,
        LOCAL_READ,
        BINDLESS,
        DESCRIPTOR_BUFFER,
        OPAQUE_TERRAIN,
        TERRAIN_OIT,
        MESH
    }

    private static long rejected;

    private ProgramAvailability() {
    }

    public static void beginReload() {
        RenderSystem.assertOnRenderThread();
        rejected = 0L;
    }

    public static boolean allows(Feature feature) {
        return (rejected & (1L << feature.ordinal())) == 0L;
    }

    public static void reject(Feature feature, BackendUnavailableException failure) {
        RenderSystem.assertOnRenderThread();
        rejected |= 1L << feature.ordinal();
        FlwPrograms.LOGGER.error("Rejecting shader feature '{}' for this resource generation", feature, failure);
    }

    public static void run(Feature feature, Runnable action) {
        if (!allows(feature)) return;
        try {
            action.run();
        } catch (Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            throw new Failure(feature, failure);
        }
    }

    public static Feature insert(OitInsertMode mode) {
        return switch (mode) {
            case MLAB -> Feature.INSERT_MLAB;
            case KBUFFER -> Feature.INSERT_KBUFFER;
            case ABUFFER -> Feature.INSERT_ABUFFER;
        };
    }

    public static final class Failure extends BackendUnavailableException {
        private final Feature feature;

        public Failure(Feature feature, BackendUnavailableException cause) {
            super("Shader feature " + feature + " is unavailable", cause);
            this.feature = feature;
        }

        public Feature feature() {
            return feature;
        }
    }
}
