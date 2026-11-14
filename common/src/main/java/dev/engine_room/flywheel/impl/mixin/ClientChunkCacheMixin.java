package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.util.TerrainReceivers;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    ClientLevel level;

    @Unique
    private int flywheel$centerX;

    @Unique
    private int flywheel$centerZ;

    @Unique
    private int flywheel$radius;

    @Unique
    private final List<LevelChunk> flywheel$held = new ArrayList<>();

    @Shadow
    private static int calculateStorageRange(int viewRange) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void flywheel$initRing(ClientLevel level, int serverChunkRadius, CallbackInfo ci) {
        flywheel$radius = calculateStorageRange(serverChunkRadius);
    }

    // A centre move or radius change leaves some chunks out of the ring without ClientLevel.unload: their block
    // entities are never removed, so their visuals are.
    @Inject(method = "updateViewCenter", at = @At("HEAD"), require = 1)
    private void flywheel$holdMovedRing(int x, int z, CallbackInfo ci) {
        flywheel$holdDeparting(x, z, flywheel$radius);
    }

    @Inject(method = "updateViewRadius", at = @At("HEAD"), require = 1)
    private void flywheel$holdResizedRing(int viewRange, CallbackInfo ci) {
        flywheel$holdDeparting(flywheel$centerX, flywheel$centerZ, calculateStorageRange(viewRange));
    }

    @Unique
    private void flywheel$holdDeparting(int centerX, int centerZ, int radius) {
        var manager = VisualizationManagerImpl.get(level);
        if (manager == null || manager.farVisuals() == null) {
            return;
        }

        int minX = flywheel$centerX - flywheel$radius;
        int maxX = flywheel$centerX + flywheel$radius;
        int minZ = flywheel$centerZ - flywheel$radius;
        int maxZ = flywheel$centerZ + flywheel$radius;
        int overlapMinX = Math.max(minX, centerX - radius);
        int overlapMaxX = Math.min(maxX, centerX + radius);
        int overlapMinZ = Math.max(minZ, centerZ - radius);
        int overlapMaxZ = Math.min(maxZ, centerZ + radius);
        var source = (ClientChunkCache) (Object) this;
        if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
            flywheel$holdRectangle(source, minX, minZ, maxX, maxZ);
            return;
        }

        flywheel$holdRectangle(source, minX, minZ, maxX, overlapMinZ - 1);
        flywheel$holdRectangle(source, minX, overlapMaxZ + 1, maxX, maxZ);
        flywheel$holdRectangle(source, minX, overlapMinZ, overlapMinX - 1, overlapMaxZ);
        flywheel$holdRectangle(source, overlapMaxX + 1, overlapMinZ, maxX, overlapMaxZ);
    }

    @Unique
    private void flywheel$holdRectangle(ClientChunkCache source, int minX, int minZ, int maxX, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                LevelChunk chunk = source.getChunkNow(x, z);
                if (chunk != null) {
                    flywheel$held.add(chunk);
                }
            }
        }
    }

    @Inject(method = "updateViewCenter", at = @At("TAIL"), require = 1)
    private void flywheel$movedRing(int x, int z, CallbackInfo ci) {
        flywheel$centerX = x;
        flywheel$centerZ = z;
        flywheel$releaseDropped();
    }

    @Inject(method = "updateViewRadius", at = @At("TAIL"), require = 1)
    private void flywheel$resizedRing(int viewRange, CallbackInfo ci) {
        flywheel$radius = calculateStorageRange(viewRange);
        flywheel$releaseDropped();
    }

    @Unique
    private void flywheel$releaseDropped() {
        var source = (ClientChunkCache) (Object) this;
        var manager = VisualizationManagerImpl.get(level);
        for (LevelChunk chunk : flywheel$held) {
            if (manager != null && source.getChunkNow(chunk.getPos().x(), chunk.getPos().z()) != chunk) {
                manager.onChunkDropping(chunk);
                chunk.getBlockEntities().values().forEach(manager.blockEntities()::queueRemove);
            }
        }
        flywheel$held.clear();
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), require = 1)
    private void flywheel$chunkReplaced(CallbackInfoReturnable<LevelChunk> cir) {
        var chunk = cir.getReturnValue();
        if (chunk == null) return;
        TerrainReceivers.invalidateChunk(level, chunk.getPos().x(), chunk.getPos().z());
        var manager = VisualizationManagerImpl.get(level);
        if (manager != null) {
            manager.onChunkChanged(chunk.getPos().x(), chunk.getPos().z());
            manager.onChunkLoaded(chunk);
        }
    }

    @Inject(method = "drop", at = @At("HEAD"), require = 1)
    private void flywheel$chunkDropping(ChunkPos pos, CallbackInfo ci) {
        var manager = VisualizationManagerImpl.get(level);
        LevelChunk chunk = ((ClientChunkCache) (Object) this).getChunkNow(pos.x(), pos.z());
        if (manager != null && chunk != null) {
            manager.onChunkDropping(chunk);
        }
    }

    @Inject(method = "drop", at = @At("RETURN"), require = 1)
    private void flywheel$chunkDropped(ChunkPos pos, CallbackInfo _) {
        TerrainReceivers.invalidateChunk(level, pos.x(), pos.z());
        var manager = VisualizationManagerImpl.get(level);
        if (manager != null) manager.onChunkChanged(pos.x(), pos.z());
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"), require = 1)
    private void flywheel$onLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo _) {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager != null) {
            manager.onLightUpdate(pos, layer);
        }
    }
}
