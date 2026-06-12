package dev.engine_room.flywheel.backend.gl;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;

public final class GlLayerTexture extends GlTexture {
    private GlLayerTexture(int viewId, String label, GpuFormat format, int width, int height) {
        // GpuDevice.backend is access-widened (NeoForge accesstransformer.cfg / Fabric accesswidener).
        super(USAGE_RENDER_ATTACHMENT | USAGE_TEXTURE_BINDING, label, format, width, height, 1, 1,
                viewId, ((GlDevice) RenderSystem.getDevice().backend).frameBufferCache());
    }

    public static GlTexture wrap(int viewId, String label, GpuFormat format, int width, int height) {
        return new GlLayerTexture(viewId, label, format, width, height);
    }
}
