package dev.engine_room.flywheel.impl.visualization;

import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwImpl;
import dev.engine_room.flywheel.impl.compat.VitrailCompat;
import net.minecraft.client.Minecraft;

/**
 * Suspends visualization while another mod renders the world in place of {@code LevelRenderer.render}, or shades it
 * through programs that never see instances ({@link VitrailCompat}): such renderers drive vanilla renderers, which
 * must not skip visualized geometry. Render thread only.
 */
public final class WorldRenderOwnership {
    // Consecutive frames without LevelRenderer.render: a one-off skip must not reset every visual.
    private static final int SUSPEND_FRAMES = 3;

    private static boolean levelRendered;
    private static int externalFrames;
    private static boolean transitionQueued;

    private WorldRenderOwnership() {
    }

    public static void beginLevelRender() {
        levelRendered = false;
    }

    public static void onLevelRendererRender() {
        levelRendered = true;
    }

    public static void endLevelRender() {
        externalFrames = levelRendered ? 0 : externalFrames + 1;
        boolean suspended = BackendManagerImpl.isSuspended();
        boolean packDrawing = VitrailCompat.drawingPack();
        if (transitionQueued || (suspended ? !levelRendered || packDrawing
                : externalFrames < SUSPEND_FRAMES && !packDrawing)) {
            return;
        }
        transitionQueued = true;
        // Between frames: resetting mid-render would free resources the frame still uses.
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            transitionQueued = false;
            if (suspended) {
                FlwImpl.LOGGER.info("World rendering returned to Minecraft; resuming visualization");
                BackendManagerImpl.setSuspended(false);
                // Block entities re-register through section compilation.
                minecraft.levelExtractor.allChanged();
            } else {
                BackendManagerImpl.setSuspended(true);
                if (packDrawing) {
                    FlwImpl.LOGGER.info("A Vitrail shader pack draws the world; suspending visualization");
                    // Its pack draws Sodium's sections: block entities re-register only via section compile.
                    minecraft.levelExtractor.allChanged();
                } else {
                    FlwImpl.LOGGER.info("Another mod renders the world; suspending visualization");
                    VisualizationManagerImpl.resetAll();
                }
            }
        });
    }
}
