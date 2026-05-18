package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;

public class NoiseTextures {
    public static final ResourceLocation NOISE_TEXTURE = ResourceUtil.rl("textures/flywheel/noise/blue.png");

    public static DynamicTexture BLUE_NOISE;

    public static void reload(IResourceManager manager) {
        if (BLUE_NOISE != null) {
            BLUE_NOISE.deleteGlTexture();
            BLUE_NOISE = null;
        }

        try (IResource resource = manager.getResource(NOISE_TEXTURE);
             InputStream is = resource.getInputStream()) {
            var image = ImageIO.read(is);
            if (image == null) {
                return;
            }

            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = new int[w * h];
            image.getRGB(0, 0, w, h, argb, 0, w);

            BLUE_NOISE = new DynamicTexture(w, h);
            int[] data = BLUE_NOISE.getTextureData();
            System.arraycopy(argb, 0, data, 0, argb.length);
            BLUE_NOISE.updateDynamicTexture();

            GlTextureUnit.T0.makeActive();
            GlStateManager.bindTexture(BLUE_NOISE.getGlTextureId());

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

            GlStateManager.bindTexture(0);
        } catch (IOException e) {
        }
    }

}
