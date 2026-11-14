package dev.engine_room.flywheel.impl.compat;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.CardinalLighting;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.List;

/**
 * Direct native shading of captured static meshes in DH's selected terrain sections.
 */
public final class DhStaticDrawProvider implements DhStaticVisuals.DrawProvider {
    private @Nullable FarStaticDraw draw;
    private FarStaticDraw.TerrainDepth mainDepth;
    private int opaqueDepth;
    private int opaqueWidth;
    private int opaqueHeight;

    @Override
    public boolean available() {
        return GlCompat.SUPPORTS_INDIRECT && GlCompat.SUPPORTS_SHADER_DRAW_PARAMETERS;
    }

    @Override
    public void prepareMain(FarHost host, RenderContext context) {
        if (opaqueDepth == 0 || opaqueWidth != host.width() || opaqueHeight != host.height()) {
            if (opaqueDepth != 0) GL11C.glDeleteTextures(opaqueDepth);
            opaqueDepth = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(opaqueDepth, 1, GL30C.GL_DEPTH_COMPONENT32F, host.width(), host.height());
            opaqueWidth = host.width();
            opaqueHeight = host.height();
        }
        GL43C.glCopyImageSubData(host.depth(), GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                opaqueDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, host.width(), host.height(), 1);
        mainDepth = new FarStaticDraw.TerrainDepth(opaqueDepth,
                new Matrix4f(host.viewToHostClip()).mul(context.modelView()), host.zeroToOne(), host.reverseZ());
    }

    @Override
    public void draw(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches, RenderParams params) {
        if (draw == null) draw = new FarStaticDraw(false);
        var mc = Minecraft.getInstance();
        var context = VisualizationManagerImpl.getOrThrow(mc.level).currentFrameContext();
        var host = DistantHorizonsCompat.farHost(params);
        draw.target(host.color(), host.depth(), host.width(), host.height());
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        if (!draw.bindNative(new Matrix4f(host.viewToHostClip()).mul(context.modelView()),
                context.camera().position(), host.zeroToOne(), host.reverseZ(),
                mc.level.dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER)) return;
        batches(meshes, batches);
    }

    @Override
    public void drawMain(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches, RenderContext context) {
        if (draw == null) draw = new FarStaticDraw(false);
        var mc = Minecraft.getInstance();
        var target = mc.gameRenderer.mainRenderTarget();
        draw.target(((GlTexture) target.getColorTexture()).glId(), ((GlTexture) target.getDepthTexture()).glId(),
                target.width, target.height);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        if (!draw.bindNative(new Matrix4f(context.projection()).mul(context.modelView()), context.camera().position(),
                true, true, mc.level.dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER)) return;
        draw.mainRadius(LodCompat.vanillaRadius());
        draw.terrainDepth(mainDepth, 13);
        batches(meshes, batches);
    }

    private void batches(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches) {
        for (var batch : batches) {
            draw.geometry(meshes, batch.mappingBuffer(), batch.clipBuffer());
            draw.nativeBatch(meshes, batch.commands());
        }
    }

    @Override
    public void close() {
        if (draw != null) draw.close();
        draw = null;
        if (opaqueDepth != 0) GL11C.glDeleteTextures(opaqueDepth);
        opaqueDepth = 0;
        mainDepth = null;
    }
}
