package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.lib.util.OverlayTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL13;

public class TextureBinder {
    private static DynamicTexture overlayTexture;
    private static int cachedLightmapTex;

    public static void bind(ResourceLocation resourceLocation) {
        // 1.12.2: TextureManager.getTexture(rl) returns null for textures not yet loaded, so the
        // earlier "lookup-then-bind" path silently bound 0 the first time the crumbling overlay
        // hit destroy_stage_N.png (vanilla never loads those — it uses the BLOCKS atlas sprites
        // instead). bindTexture(rl) loads + caches + binds in one call against the active unit,
        // which the caller has already set via Samplers.CRUMBLING.makeActive().
        Minecraft.getMinecraft().getTextureManager().bindTexture(resourceLocation);
    }

    public static void bindLightAndOverlay() {
        // Samplers.OVERLAY (T1) is the same GL unit vanilla binds the lightmap to, so read the
        // lightmap id before we clobber that unit and cache it (the lightmap texture is stable for
        // the session). Read BEFORE lazily creating the overlay texture: DynamicTexture's ctor binds
        // the new texture on whatever unit is active, which may be this one.
        int lightmapUnit = OpenGlHelper.lightmapTexUnit - GL13.GL_TEXTURE0;
        int bound = GlStateManager.textureState[lightmapUnit].textureName;
        int overlayTex = overlayTextureId();
        if (bound != overlayTex) {
            cachedLightmapTex = bound;
        }

        Samplers.OVERLAY.makeActive();
        GlStateManager.bindTexture(overlayTex);
        Samplers.LIGHT.makeActive();
        GlStateManager.bindTexture(cachedLightmapTex);
    }

    public static void resetLightAndOverlay() {
        // Restore the lightmap on T1: vanilla passes after us (e.g. pass-1 entities following the OIT
        // hook) sample that unit without re-running enableLightmap.
        Samplers.OVERLAY.makeActive();
        GlStateManager.bindTexture(cachedLightmapTex);
        // Leave T0 active, as MaterialRenderState.reset() did before this call.
        Samplers.DIFFUSE.makeActive();
    }

    private static int overlayTextureId() {
        if (overlayTexture == null) {
            overlayTexture = new DynamicTexture(16, 16);
            OverlayTexture.fillTextureData(overlayTexture.getTextureData());
            overlayTexture.updateDynamicTexture();
        }
        return overlayTexture.getGlTextureId();
    }
}
