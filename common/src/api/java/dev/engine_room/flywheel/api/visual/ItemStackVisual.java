package dev.engine_room.flywheel.api.visual;

import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;

/**
 * An item stack drawn inside a host visual (a held, dropped, framed or displayed item). The host creates, poses and
 * deletes it on the host's own threads, frame plan workers included; the visualization manager never sees it.
 */
public interface ItemStackVisual {
    /**
     * The host's stack changed; same item and display context.
     *
     * @return {@code false} to be deleted and created anew for this stack.
     */
    boolean update(ItemStack stack);

    /**
     * Draw this frame.
     *
     * @param pose the frame a {@code SpecialModelRenderer} receives for this stack: render-origin relative, the item
     *             model's display transform applied.
     */
    void beginFrame(Matrix4fc pose, int light, int overlay, float partialTick);

    /**
     * Not drawn this frame; {@link #beginFrame} reveals again.
     */
    void hide();

    void delete();
}
