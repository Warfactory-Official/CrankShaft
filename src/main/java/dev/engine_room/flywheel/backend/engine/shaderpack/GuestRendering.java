package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.impl.pipeline.vertex.ExtendedVertexFormats;
import dev.engine_room.flywheel.impl.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.fml.common.Loader;

public final class GuestRendering {
    private GuestRendering() {
    }

    public static VertexFormat blockVertexFormat() {
        return Loader.isModLoaded(
                "ausm") && GuestRenderPass.active() ? ExtendedVertexFormats.PIPELINE_BLOCK : DefaultVertexFormats.BLOCK;
    }

    public static float tangentScale() {
        return GuestHost.current().tangentScale();
    }

    public static boolean ownsTranslucentChunks() {
        var manager = VisualizationManagerImpl.get(Minecraft.getMinecraft().world);
        if (manager == null) return false;
        var engine = manager.getEngineImpl();
        return engine != null && engine.drawManager() instanceof GuestRenderer;
    }

    public static boolean renderNativeTerrain(BlockRenderLayer layer) {
        if (!GuestRenderPass.active()) return false;
        var minecraft = Minecraft.getMinecraft();
        var manager = VisualizationManagerImpl.get(minecraft.world);
        if (manager == null || manager.getEngineImpl() == null
                || !(manager.getEngineImpl().drawManager() instanceof GuestIndirectDrawManager indirect)) return false;
        var chunks = minecraft.renderGlobal.renderContainer.renderChunks;
        indirect.renderNativeTerrain(layer, chunks);
        chunks.clear();
        return true;
    }

    public static boolean replaysShadowTerrain() {
        return ownsTranslucentChunks() && GuestHost.current().contracts().properties.shadowEnabled();
    }

    public static void renderShadowOpaque(float partialTicks) {
        var minecraft = Minecraft.getMinecraft();
        var manager = VisualizationManagerImpl.get(minecraft.world);
        if (manager == null) return;
        var context = RenderContextImpl.create(minecraft.renderGlobal, minecraft.world, minecraft.getRenderViewEntity(),
                partialTicks);
        manager.renderDispatcher().onStartLevelRender(context);
        if (ownsTranslucentChunks()) manager.renderDispatcher().afterEntities(context);
    }

    public static void renderShadowTranslucent() {
        if (!ownsTranslucentChunks()) return;
        var manager = VisualizationManagerImpl.get(Minecraft.getMinecraft().world);
        manager.renderDispatcher().afterTranslucent(manager.currentFrameContext());
    }
}
