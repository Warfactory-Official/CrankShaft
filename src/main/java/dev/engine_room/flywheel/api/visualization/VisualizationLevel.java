package dev.engine_room.flywheel.api.visualization;

import net.minecraft.world.IBlockAccess;

/**
 * A marker interface custom levels can override to indicate
 * that block entities and entities inside the level should
 * render with Flywheel.
 * <br>
 * Minecraft#world is special cased and will support Flywheel by default.
 */
public interface VisualizationLevel extends IBlockAccess {
    default boolean supportsVisualization() {
        return true;
    }
}
