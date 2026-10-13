package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.engine_room.flywheel.impl.event.RenderPassDescriptorAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassDescriptorMixin implements RenderPassDescriptorAccess {
    @Unique
    private RenderPassDescriptor flywheel$descriptor;

    @Override
    public RenderPassDescriptor flywheel$descriptor() {
        return flywheel$descriptor;
    }

    @Override
    public void flywheel$descriptor(RenderPassDescriptor descriptor) {
        flywheel$descriptor = descriptor;
    }
}
