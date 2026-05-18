package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.Samplers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL13;

public class TextureBinder {

    public static void bind(ResourceLocation resourceLocation) {
        // 1.12.2: TextureManager.getTexture(rl) returns null for textures not yet loaded, so the
        // earlier "lookup-then-bind" path silently bound 0 the first time the crumbling overlay
        // hit destroy_stage_N.png (vanilla never loads those — it uses the BLOCKS atlas sprites
        // instead). bindTexture(rl) loads + caches + binds in one call against the active unit,
        // which the caller has already set via Samplers.CRUMBLING.makeActive().
        Minecraft.getMinecraft().getTextureManager().bindTexture(resourceLocation);
    }

    public static void bindLightAndOverlay() {
        int lightmapTex = getActiveLightmapTexture();
        Samplers.LIGHT.makeActive();
        GlStateManager.bindTexture(lightmapTex);
    }

    public static void resetLightAndOverlay() {
    }

    private static int getActiveLightmapTexture() {
        // 1.12.2 EntityRenderer.updateLightmap() binds the live lightmap DynamicTexture at
        // OpenGlHelper.lightmapTexUnit (typically GL_TEXTURE1) every tick. The binding lives in
        // GlStateManager.textureState[unit].textureName (public field, length-8 array indexed by
        // unit offset from GL_TEXTURE0). Reading the cache directly avoids the
        // setActiveTexture + glGetInteger(GL_TEXTURE_BINDING_2D) + restore round-trip; lightmap
        // unit is always within the [0,8) low-unit window the cache covers.
        int lightmapUnit = OpenGlHelper.lightmapTexUnit - GL13.GL_TEXTURE0;
        return GlStateManager.textureState[lightmapUnit].textureName;
    }
}
