package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.SodiumOccluderCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;

@Mixin(ChunkMeshBufferBuilder.class)
abstract class SodiumBufferOccludersMixin implements SodiumOccluderCapture.Buffer {
    @Shadow
    @Final
    private int stride;
    @Shadow
    private MemorySegment buffer;
    @Shadow
    private int vertexCount;
    @Unique
    private boolean lighting$translucent;
    @Unique
    private SodiumOccluderCapture.@Nullable Recording lighting$recording;

    @Override
    public void lighting$translucent(boolean translucent) {
        lighting$translucent = translucent;
    }

    @Override
    public void lighting$recording(SodiumOccluderCapture.@Nullable Recording recording) {
        lighting$recording = recording;
    }

    @Inject(method = "start", at = @At("HEAD"))
    private void lighting$begin(int sectionIndex, CallbackInfo ci) {
        lighting$recording = lighting$translucent ? null : SodiumOccluderCapture.current(this);
    }

    // Iris replaces colour alpha/material bits and changes stride; preserve the logical quad at the actual write.
    @Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder;write(JI[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J", shift = At.Shift.AFTER))
    private void lighting$quad(ChunkVertexEncoder.Vertex[] vertices, int material, CallbackInfo ci) {
        if (lighting$recording != null)
            lighting$recording.quad(buffer.address() + (long) vertexCount * stride, stride, material, vertices);
    }
}
