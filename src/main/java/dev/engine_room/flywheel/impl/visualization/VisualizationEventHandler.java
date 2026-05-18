package dev.engine_room.flywheel.impl.visualization;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

public final class VisualizationEventHandler {
    private VisualizationEventHandler() {
    }

    public static void onClientTick(Minecraft minecraft, World level) {
        // The game won't be paused in the tick event, but let's make sure there's a player.
        if (minecraft.player == null) {
            return;
        }

        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager == null) {
            return;
        }

        manager.tick();
    }

    public static void onWorldUnload(World level) {
        if (!level.isRemote) {
            return;
        }
        VisualizationManagerImpl.reset(level);
    }
}
