package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.engine.shaderpack.NothiriumTerrain;
import meldexun.nothirium.api.renderer.IVBOPart;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.compat.NothiriumShadowCompileScheduling", remap = false)
public abstract class NothiriumTerrainCaptureMixin {
    // Keep AUSM's visibility, stride validation, rebuild requests and draw statistics during collection.
    @Inject(method = "drawChunksWithLayerState", at = @At("HEAD"), require = 1)
    private void flw$begin(BlockRenderLayer layer, @Coerce Object reflection, Object pass, Iterable<?> chunks,
                           double x, double y, double z, double maximumDistance, boolean collectState,
                           int fallbackId, short renderType, boolean pipelineStride, CallbackInfoReturnable<Object> ci) {
        NothiriumTerrain.layer(layer, fallbackId, renderType);
    }

    @Inject(method = "drawChunksWithLayerState", at = @At("RETURN"), require = 1)
    private void flw$end(CallbackInfoReturnable<Object> ci) { NothiriumTerrain.layer(null); }

    // The scalar collection loop feeds CrankShaft's pooled multi-draw; AUSM's indirect stream uses a different vertex ABI.
    @Inject(method = "tryDrawPreparedPipelineChunksIndirect", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$collect(CallbackInfoReturnable<Object> ci) {
        if (NothiriumTerrain.capturing()) ci.setReturnValue(null);
    }

    @WrapOperation(method = "drawChunks", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDrawArrays(III)V"), require = 2)
    private void flw$generic(int primitive, int first, int count, Operation<Void> original,
                             @Local(name = "part") Object part, @Local(name = "vbo") int buffer,
                             @Local(name = "stride") int stride, @Local(name = "chunkX") int x,
                             @Local(name = "chunkY") int y, @Local(name = "chunkZ") int z) {
        if (NothiriumTerrain.capturing()) NothiriumTerrain.capture(part, primitive, first, count, buffer, stride, x, y, z);
        else original.call(primitive, first, count);
    }

    @WrapOperation(method = "drawPreparedPipelineChunks", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDrawArrays(III)V"), require = 2)
    private void flw$prepared(int primitive, int first, int count, Operation<Void> original,
                              @Local(name = "part") IVBOPart part, @Local(name = "vbo") int buffer,
                              @Local(name = "stride") int stride, @Local(name = "chunkX") int x,
                              @Local(name = "chunkY") int y, @Local(name = "chunkZ") int z) {
        if (NothiriumTerrain.capturing()) NothiriumTerrain.capture(part, primitive, first, count, buffer, stride, x, y, z);
        else original.call(primitive, first, count);
    }

    // Captured draws carry absolute section origins; their original program's per-section offset is unused.
    @WrapOperation(method = {"drawChunks", "drawPreparedPipelineChunks"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glUniform3f(IFFF)V"), require = 1)
    private void flw$offset(int location, float x, float y, float z, Operation<Void> original) {
        if (!NothiriumTerrain.capturing() || x == 0 && y == 0 && z == 0) original.call(location, x, y, z);
    }
}
