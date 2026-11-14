package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkBuilderMeshingTask.class)
abstract class ReceiverMeshingTaskMixin {
    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput flywheel$receiverScope(ChunkBuildContext context, CancellationToken cancellation,
                                                    Operation<ChunkBuildOutput> original) {
        var section = ((ReceiverChunkTaskAccessor) this).flywheel$receiverSection();
        var level = ((ReceiverLevelSliceAccessor) (Object) context.cache.getWorldSlice()).flywheel$receiverLevel();
        try (var _ = TerrainReceiverCompilation.begin(level,
                SectionPos.asLong(section.getOriginX() >> 4, section.getOriginY() >> 4, section.getOriginZ() >> 4))) {
            return original.call(context, cancellation);
        }
    }
}
