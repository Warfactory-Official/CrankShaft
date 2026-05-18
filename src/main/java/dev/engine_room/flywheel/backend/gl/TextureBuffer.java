package dev.engine_room.flywheel.backend.gl;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

public class TextureBuffer extends GlObject {
    public static final int MAX_TEXELS = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
    public static final int MAX_BYTES = MAX_TEXELS * 16; // 4 channels * 4 bytes
    private final int format;

    public TextureBuffer() {
        this(GL30.GL_RGBA32UI);
    }

    public TextureBuffer(int format) {
        handle(GL11.glGenTextures());
        this.format = format;
    }

    public void bind(int buffer) {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, handle());
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, format, buffer);
    }

    @Override
    protected void deleteInternal(int handle) {
        GlStateManager.deleteTexture(handle);
    }
}
