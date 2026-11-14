package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Nearest instance OIT depth per pixel, for passes that composite over the main target after the OIT seam and must
 * tell an instance surface from the terrain behind it: OIT instances never write depth. Recorded by the seam only
 * while such a pass {@link #want wanted} it in the previous frame. Render thread.
 */
public final class OitInstanceCoverage {
    private static @Nullable GpuTexture texture;
    private static @Nullable GpuTextureView view;
    private static boolean wanted;
    private static boolean recording;
    private static boolean seamRan;
    private static boolean recorded;

    private OitInstanceCoverage() {
    }

    /**
     * The engine's frame start, before any pass: the previous frame's seam and coverage no longer apply.
     */
    public static void beginFrame() {
        seamRan = false;
        recorded = false;
    }

    /**
     * The engine's OIT seam, before its draw manager runs; the draw manager then records coverage when
     * {@link #recording()}.
     */
    public static void seam() {
        seamRan = true;
        recording = wanted;
        wanted = false;
    }

    /**
     * Whether this frame's OIT seam already ran, so a pass composited now lies over its instances.
     */
    public static boolean seamRan() {
        return seamRan;
    }

    /**
     * Asks the next frame's seam to record coverage.
     */
    public static void want() {
        wanted = true;
    }

    public static boolean recording() {
        return recording;
    }

    /**
     * This frame's coverage, {@code .r} = minus the nearest instance linear depth, {@code -far} where no instance
     * drew; {@code null} when the seam recorded none.
     */
    public static @Nullable GpuTextureView view() {
        return recorded ? view : null;
    }

    /**
     * Opens the coverage pass: instance OIT producers in {@code DEPTH_RANGE} mode, depth-tested against
     * {@code mainDepth}, which they never write.
     */
    public static RenderPass begin(CommandEncoder encoder, GpuTextureView mainDepth, int width, int height,
                                   float far) {
        if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            close();
            texture = RenderSystem.getDevice().createTexture(() -> "flywheel:oit/instance_coverage",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    OitFramebuffer.INSTANCE_BOUNDS_FORMAT, width, height, 1, 1);
            view = RenderSystem.getDevice().createTextureView(texture);
        }
        recording = false;
        recorded = true;
        return encoder.createRenderPass(RenderPassOps.builder(() -> "flywheel:oit/instance_coverage")
                                                     .withColorAttachment(view,
                                                             Optional.of(new Vector4f(-far, -far, 0.0f, 0.0f)))
                                                     .withDepthAttachment(mainDepth, OptionalDouble.empty())
                                                     .withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                                                     .build());
    }

    public static void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        recorded = false;
    }
}
