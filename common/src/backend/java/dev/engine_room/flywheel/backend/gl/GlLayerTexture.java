package dev.engine_room.flywheel.backend.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.backend.opengl.GlTexture;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
#endif

public final class GlLayerTexture extends GlTexture {
    private GlLayerTexture(int viewId, String label, GpuFormat format, int width, int height) {
        // GpuDevice.backend is access-widened (NeoForge accesstransformer.cfg / Fabric accesswidener).
        super(USAGE_RENDER_ATTACHMENT | USAGE_TEXTURE_BINDING, label, format, width, height, 1, 1,
                viewId,
                #if MC >= 26.3
                ((GlDevice) ((FrontendGpuDevice) RenderSystem.getDevice()).backend).frameBufferCache());
                #else
                ((GlDevice) RenderSystem.getDevice().backend).frameBufferCache());
                #endif
    }

    public static GlTexture wrap(int viewId, String label, GpuFormat format, int width, int height) {
        return new GlLayerTexture(viewId, label, format, width, height);
    }
}
