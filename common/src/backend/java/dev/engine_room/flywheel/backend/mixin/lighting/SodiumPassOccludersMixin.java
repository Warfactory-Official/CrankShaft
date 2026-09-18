package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.SodiumOccluderCapture;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkBuildBuffers.class)
abstract class SodiumPassOccludersMixin {
    @Shadow
    @Final
    private Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders;

    // Pass membership belongs to the pooled buffer, not to the vertex format or the pack's encoded flags.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lighting$passes(CallbackInfo ci) {
        for (var entry : builders.entrySet())
            for (var facing : ModelQuadFacing.values())
                ((SodiumOccluderCapture.Buffer) entry.getValue().getVertexBuffer(facing)).lighting$translucent(
                        entry.getKey().isTranslucent());
    }
}
