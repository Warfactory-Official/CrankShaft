package dev.engine_room.flywheel.impl.compat;

#if MC >= 26.3

import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.api.backend.RenderContext;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;

import java.util.List;

/**
 * Native material shading of static captures selected by Voxy's section command generation.
 */
public final class VoxyStaticDrawProvider implements VoxyStaticVisuals.DrawProvider {
    private @Nullable FarStaticDraw draw;

    @Override
    public boolean available(AbstractRenderPipeline pipeline) {
        return pipeline instanceof NormalRenderPipeline && GlCompat.SUPPORTS_INDIRECT;
    }

    @Override
    public void draw(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                     int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport) {
        if (draw == null) draw = new FarStaticDraw(false);
        var camera = new Vec3(viewport.cameraX, viewport.cameraY, viewport.cameraZ);
        draw.geometry(meshes, mappingBuffer, clipBuffer);
        if (!draw.bindNative(viewport.MVP, camera, pipeline.properties.isZero2One(), pipeline.properties.isReverseZ(),
                Minecraft.getInstance().level.dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER))
            return;
        for (var batch : batches) draw.nativeBatch(meshes, batch);
    }

    @Override
    public void drawMain(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                         int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport,
                         RenderContext context) {
        if (draw == null) draw = new FarStaticDraw(false);
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        draw.target(((GlTexture) target.getColorTexture()).glId(), ((GlTexture) target.getDepthTexture()).glId(),
                target.width, target.height);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        draw.geometry(meshes, mappingBuffer, clipBuffer);
        if (!draw.bindNative(new Matrix4f(context.projection()).mul(context.modelView()), context.camera().position(),
                true, true, context.level().dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER))
            return;
        draw.mainRadius(LodCompat.vanillaRadius());
        for (var batch : batches) draw.nativeBatch(meshes, batch);
    }

    @Override
    public void close(AbstractRenderPipeline pipeline) {
        if (draw != null) draw.close();
        draw = null;
    }
}

#endif
