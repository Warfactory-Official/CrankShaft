package dev.engine_room.flywheel.backend.mixin.lighting;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.backend.lighting.SodiumOccluderCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkBuilderMeshingTask.class)
abstract class SodiumMeshingOccludersMixin {
    @Shadow
    @Final
    private ChunkRenderContext renderContext;

    // The job brackets pooled worker buffers, including cancellation and failed model emission.
    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput lighting$capture(ChunkBuildContext context, CancellationToken cancellation,
                                              Operation<ChunkBuildOutput> original) {
        var recording = SodiumOccluderCapture.begin(renderContext.getOrigin().asLong());
        try {
            var output = original.call(context, cancellation);
            if (output != null) ((SodiumOccluderCapture.Carrier) output).lighting$occluders(recording);
            return output;
        } finally {
            SodiumOccluderCapture.end(recording);
        }
    }
}
