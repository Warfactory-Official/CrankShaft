package dev.engine_room.flywheel.impl.event;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;

/**
 * Creation-time attachment ownership retained by {@code FrontendRenderPass}. The command encoder records the
 * descriptor before returning a pass; level rendering consumes it when yielding the main pass to the engine.
 * Recording and consumption both run on the render thread.
 */
public interface RenderPassDescriptorAccess {
    RenderPassDescriptor flywheel$descriptor();

    void flywheel$descriptor(RenderPassDescriptor descriptor);
}
