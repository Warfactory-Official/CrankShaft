package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

/** Hardware depth tests for guest light drawn after the native material capture. */
final class GuestCaptureDepth {
    private int width = -1;
    private int height = -1;
    private int format;
    private int texture;

    void capture(GpuTexture source, int width, int height) {
        int mainDepth = ((GlTexture) source).glId();
        int depthFormat = GL45C.glGetTextureLevelParameteri(mainDepth, 0, GL30C.GL_TEXTURE_INTERNAL_FORMAT);
        if (this.width != width || this.height != height || format != depthFormat) {
            delete();
            this.width = width;
            this.height = height;
            format = depthFormat;
            texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(texture, 1, format, width, height);
        }
        GL43C.glCopyImageSubData(mainDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, texture, GL11C.GL_TEXTURE_2D,
                0, 0, 0, 0, width, height, 1);
    }

    int texture() {
        return texture;
    }

    void delete() {
        if (texture == 0) return;
        GlStateManager._deleteTexture(texture);
        texture = 0;
        width = height = -1;
    }
}
