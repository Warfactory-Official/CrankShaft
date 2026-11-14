package dev.engine_room.flywheel.impl.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkBuilderTask.class)
public interface ReceiverChunkTaskAccessor {
    @Accessor("section")
    RenderSection flywheel$receiverSection();
}
