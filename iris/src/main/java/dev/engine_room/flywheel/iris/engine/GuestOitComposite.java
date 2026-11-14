package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import dev.engine_room.flywheel.iris.compile.ForwardOit;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class GuestOitComposite {
    private GuestOitComposite() {
    }

    // The pass targets are placeholders: the composite program binds the pack framebuffer.
    static void draw(String label, boolean shadow) {
        var parent = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        var forward = shadow ? null : ForwardOit.get(parent);
        if (forward != null) {
            GuestPipelines.resolveForwardWater();
            forward.gathered();
            return;
        }
        drawNow(label, shadow, false, true, null);
    }

    /**
     * Resolves layers behind the captured front surface at the pack's lighting boundary.
     */
    public static void drawForward(ForwardOit.SnapshotRect rectangle) {
        if (rectangle.empty()) return;
        drawNow("flywheel:iris/forward_wavelet_composite", false, false, false, rectangle);
    }

    public static void drawForwardWater(ForwardOit.SnapshotRect rectangle) {
        drawNow("flywheel:iris/forward_water", false, true, false, rectangle);
    }

    private static void drawNow(String label, boolean shadow, boolean water, boolean fillDepth,
                                ForwardOit.@Nullable SnapshotRect rectangle) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var descriptor = RenderPassOps.builder(() -> label)
                                      .withColorAttachment(colorView)
                                      .withDepthAttachment(depthView)
                                      .build();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            pass.setRenderPipeline(water ? GuestPipelines.forwardWater(colorView.texture().getFormat())
                    : GuestPipelines.oitComposite(shadow));
            if (rectangle != null)
                pass.enableScissor(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
            pass.draw(3, 1, 0, 0);
            if (fillDepth) {
                pass.setRenderPipeline(GuestPipelines.oitDepth(shadow));
                pass.draw(3, 1, 0, 0);
            }
        }
    }
}
