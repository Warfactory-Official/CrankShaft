package dev.engine_room.flywheel.impl.compat;

#if MC >= 26.3

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.common.world.WorldEngine;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Native Voxy section submission for frozen captured meshes. All calls run on the render thread.
 */
public final class VoxyStaticVisuals {
    private static @Nullable DrawProvider nativeProvider = new VoxyStaticDrawProvider();
    private static @Nullable DrawProvider packProvider;

    private VoxyStaticVisuals() {
    }

    public static void registerNative(DrawProvider provider) {
        nativeProvider = provider;
    }

    public static void registerPack(DrawProvider provider) {
        packProvider = provider;
    }

    public static @Nullable VoxyStaticSections current() {
        var renderer = IVoxyRenderSystemHolder.getNullable();
        return renderer == null ? null : renderer.flywheel$staticSections();
    }

    public static boolean available() {
        var sections = current();
        return sections != null && sections.available();
    }

    public static @Nullable DrawProvider provider(AbstractRenderPipeline pipeline) {
        return pipeline instanceof NormalRenderPipeline ? nativeProvider : packProvider;
    }

    /**
     * Called with the host's opaque destination bound, before its following terrain stages.
     */
    public static void draw(AbstractRenderPipeline pipeline, MDICViewport viewport) {
        var sections = current();
        if (sections != null) sections.draw(pipeline, viewport);
    }

    /**
     * Supplies geometry intersecting vanilla's radius using current main-view hardware depth.
     */
    public static void drawMain(RenderContext context) {
        var sections = current();
        if (sections != null) sections.drawMain(context);
    }

    public interface DrawProvider {
        boolean available(AbstractRenderPipeline pipeline);

        void draw(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                  int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport);

        void drawMain(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                      int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport, RenderContext context);

        default void close(AbstractRenderPipeline pipeline) {
        }
    }

    public interface WorldAccess {
        VoxyStaticSections flywheel$staticSections();
    }

    public interface GeometryAccess {
        VoxyStaticSections flywheel$staticSections();

        void flywheel$staticSections(VoxyStaticSections sections);
    }

    public interface NodeAccess {
        void flywheel$staticSections(VoxyStaticSections sections);
    }

    public interface GenerationAccess {
        WorldEngine flywheel$world();
    }

    public interface AsyncAccess {
        VoxyStaticSections flywheel$staticSections();

        void flywheel$staticRoot(long position, boolean add);
    }
}

#endif
