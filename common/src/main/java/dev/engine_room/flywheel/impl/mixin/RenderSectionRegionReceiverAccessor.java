package dev.engine_room.flywheel.impl.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionReceiverAccessor {
    @Accessor("level")
    ClientLevel flywheel$receiverLevel();
}
