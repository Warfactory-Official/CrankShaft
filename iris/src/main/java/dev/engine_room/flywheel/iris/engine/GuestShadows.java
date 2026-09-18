package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class GuestShadows {
    // Iris has no unregister: one callback for the session, inert unless the level's engine is a guest.
    private static boolean registered;

    private GuestShadows() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        IrisApi.getInstance()
               .registerShadowRenderCallback((modelView, projection, cameraX, cameraY, cameraZ, tickDelta) -> {
                   VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
                   if (manager == null || !(manager.getEngineImpl() instanceof GuestEngine)) {
                       return;
                   }
                   RenderContext context = manager.currentFrameContext();
                   if (manager.syncFrameEngine() instanceof GuestEngine engine && context != null
                           && Iris.getPipelineManager()
                                  .getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
                       engine.renderShadow(context, modelView, new Vec3(cameraX, cameraY, cameraZ), pipeline);
                   }
               });
    }

    public static void afterPreTranslucentDepth() {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null && manager.getEngineImpl() instanceof GuestEngine engine
                && Iris.getPipelineManager()
                       .getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            engine.renderShadowTranslucent(pipeline);
        }
    }
}
