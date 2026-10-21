package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.engine.shaderpack.TerrainBuffer;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer implements TerrainBuffer {
    @Unique
    private long flw$uploadVersion;
    @Unique
    private TerrainBuffer.Slice flw$sourceSlice;

    // Counts can stay equal after a block edit or translucent resort; the pooled GPU copy must still refresh.
    @Inject(method = "bufferData", at = @At("RETURN"))
    private void flw$uploaded(ByteBuffer data, CallbackInfo ci) {
        flw$uploadVersion++;
    }

    @Override
    public TerrainBuffer.Slice flywheel$sourceSlice(int buffer, long offset, boolean region) {
        var slice = flw$sourceSlice;
        if (slice == null || slice.buffer() != buffer || slice.offset() != offset || slice.region() != region)
            flw$sourceSlice = slice = new TerrainBuffer.Slice(buffer, offset, region);
        return slice;
    }

    @Override
    public long flywheel$uploadVersion() {
        return flw$uploadVersion;
    }
}
