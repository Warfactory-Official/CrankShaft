package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

public final class VoxyCompat {
    public static final boolean ACTIVE = CompatMod.VOXY.isLoaded;

    private VoxyCompat() {
    }

    public static void renderInPlaceOfCutout(ChunkRenderMatrices matrices, FogParameters fog, double x, double y,
                                             double z) {
        if (ACTIVE) {
            Internals.renderOpaque(matrices, fog, x, y, z);
        }
    }

    private static final class Internals {
        static void renderOpaque(ChunkRenderMatrices matrices, FogParameters fog, double x, double y, double z) {
            VoxyRenderSystem renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer == null) {
                return;
            }
            RenderTarget target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
            Viewport<?> viewport;
            if (IrisUtil.USED_IRIS_VIEWPORT) {
                viewport = renderer.getViewport();
                IrisUtil.USED_IRIS_VIEWPORT = false;
            } else {
                viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fog, target.width,
                        target.height, x, y, z);
            }
            renderer.renderOpaque(viewport, ((GlTextureView) target.getDepthTextureView()).glId(),
                    ((GlTextureView) target.getColorTextureView()).glId());
        }
    }
}
