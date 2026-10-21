package dev.engine_room.flywheel.backend.mixin.mod.optifine;

import dev.engine_room.flywheel.backend.engine.shaderpack.OptifineTerrainBuffer;
import dev.engine_room.flywheel.backend.engine.shaderpack.TerrainBuffer;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.optifine.render.VboRange;
import net.optifine.render.VboRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements OptifineTerrainBuffer {
    @Shadow
    private int glBufferId;
    @Shadow
    private int count;
    @Shadow
    @Final
    private VertexFormat vertexFormat;
    @Shadow(remap = false)
    private VboRegion vboRegion;
    @Shadow(remap = false)
    private VboRange vboRange;

    // Render Regions bypasses VertexBuffer.count; guest selection still needs the uploaded vertex count.
    @Inject(method = "bufferData", at = @At("RETURN"), require = 1)
    private void flw$regionCount(ByteBuffer data, CallbackInfo ci) {
        if (vboRegion != null) count = vboRange.getSize();
    }

    @Override
    public TerrainBuffer.Slice flywheel$sourceSlice() {
        var source = (TerrainBuffer) (Object) this;
        return vboRegion == null ? source.flywheel$sourceSlice(glBufferId, 0, false)
                : source.flywheel$sourceSlice(vboRegion.glBufferId,
                (long) vboRange.getPosition() * vertexFormat.getSize(), true);
    }
}
