package dev.engine_room.flywheel.backend.mixin.lighting;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.lighting.SodiumTerrainGeometry;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(RenderSectionManager.class)
abstract class SodiumTerrainCaptureMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "onSectionAdded", at = @At("RETURN"))
    private void lighting$geometrySectionLoaded(int x, int y, int z, CallbackInfo ci) {
        TerrainGeometryCache.request(level, SectionPos.asLong(x, y, z));
    }

    // Only the outputs accepted by Sodium reach this upload, and their native vertex buffers are alive.
    @WrapOperation(method = "processChunkBuildResults", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void lighting$captureTerrain(RenderRegionManager regions, Collection<BuilderTaskOutput> outputs,
                                         UniformBufferManager uniforms, Operation<Void> original) {
        var captures = SodiumTerrainGeometry.capture(level, outputs);
        original.call(regions, outputs, uniforms);
        for (var capture : captures) capture.publish();
    }

    @ModifyExpressionValue(method = "scheduleRebuild", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;isBuilt()Z"))
    private boolean lighting$allowInitialAoBuild(boolean built, @Local RenderSection section) {
        return built || lighting$aoSection(section);
    }

    @ModifyArg(method = "scheduleRebuild", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z"), index = 1)
    private int lighting$prioritizeAoBuild(int type, @Local RenderSection section) {
        return lighting$aoSection(section) ? ChunkUpdateTypes.join(type, ChunkUpdateTypes.IMPORTANT) : type;
    }

    // AO casters are relevant independently of camera visibility and near-camera sorting priority.
    @ModifyExpressionValue(method = "submitImportantSectionTasks", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;shouldPrioritizeTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;F)Z"))
    private boolean lighting$aoBuildDistance(boolean priority, @Local RenderSection section) {
        return priority || lighting$aoSection(section);
    }

    @ModifyExpressionValue(method = "submitImportantSectionTasks", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/SectionTree;isSectionVisible(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)Z"))
    private boolean lighting$aoBuildVisibility(boolean visible, @Local RenderSection section) {
        return visible || lighting$aoSection(section);
    }

    @Unique
    private boolean lighting$aoSection(RenderSection section) {
        return TerrainGeometryCache.wants(level,
                SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }
}
