package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.api.external.methods.data.DhApiTerrainDataRepo;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(DhApiTerrainDataRepo.class)
abstract class DhApiTerrainDataRepoMixin {
    // Compat with DH 3.3.x: the API read waited, without timeout, on DH's prioritised, pausable file-handler queue.
    // Read on the calling thread; the repository opens a connection per thread.
    @WrapOperation(method = "getTerrainDataColumnArray", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/file/fullDatafile/V2/FullDataSourceProviderV2;getAsync(J)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<FullDataSourceV2> flywheel$readOnCaller(
            FullDataSourceProviderV2 provider, long pos, Operation<CompletableFuture<FullDataSourceV2>> original) {
        return CompletableFuture.completedFuture(provider.get(pos));
    }
}
