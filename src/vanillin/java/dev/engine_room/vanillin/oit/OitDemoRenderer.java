package dev.engine_room.vanillin.oit;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRendering;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.opengl.GL11;

/**
 * Fallback TESR mirroring {@link OitDemoVisual} — when the Flywheel backend is off, the
 * {@code VisualizationHelper.shouldSkipTileEntity} short-circuit allows this TESR to run and
 * render the same vanilla glass / glass_pane / stained_glass / stained_glass_pane model the
 * visual would, at the marker's own position via the vanilla {@link BlockRendererDispatcher}.
 */
public final class OitDemoRenderer extends TileEntitySpecialRenderer<TileEntityOitDemo> {
    @Override
    public void render(TileEntityOitDemo te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        World world = te.getWorld();
        BlockPos pos = te.getPos();
        IBlockState markerState = world.getBlockState(pos);
        IBlockState renderedState = OitDemoBlockAccess.renderedState(markerState);

        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int packed = world.getCombinedLight(pos, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                packed & 0xFFFF, (packed >> 16) & 0xFFFF);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        // AUSM only supplies material attributes when the buffer uses its extended block format.
        var format = Loader.isModLoaded("ausm") ? GuestRendering.blockVertexFormat() : DefaultVertexFormats.BLOCK;
        buf.begin(GL11.GL_QUADS, format);
        buf.setTranslation(x - pos.getX(), y - pos.getY(), z - pos.getZ());
        // Keep the native dispatcher's shader-pack material metadata hooks.
        mc.getBlockRendererDispatcher().renderBlock(renderedState, pos, new OitDemoBlockAccess(world), buf);
        buf.setTranslation(0, 0, 0);
        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }


}