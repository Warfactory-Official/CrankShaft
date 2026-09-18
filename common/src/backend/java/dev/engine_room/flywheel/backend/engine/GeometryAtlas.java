package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL33C;

public final class GeometryAtlas {
    public static final String SAMPLER = "_flw_geometryAtlas";
    public static final int VK_BINDING = 40;
    public static final int GL_MESH_UNIT = 10;

    private GeometryAtlas() {
    }

    public static GpuTextureView view() {
        return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    }

    public static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    public static void bind(RenderPass pass) {
        pass.bindTexture(SAMPLER, view(), sampler());
    }

    public static void bindRaw() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + GL_MESH_UNIT);
        GlStateManager._bindTexture(((GlTexture) texture).glId());
        GL33C.glBindSampler(GL_MESH_UNIT, ((GlSampler) sampler()).getId());
    }

    public static void clearRawSampler() {
        GL33C.glBindSampler(GL_MESH_UNIT, 0);
    }
}
