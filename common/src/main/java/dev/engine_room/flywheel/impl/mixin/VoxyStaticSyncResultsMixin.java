package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.far.VoxyStaticSyncPayload;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager$SyncResults")
abstract class VoxyStaticSyncResultsMixin implements VoxyStaticSyncPayload {
    @Unique
    private Long2LongOpenHashMap flywheel$staticRevisions = new Long2LongOpenHashMap();

    @Inject(method = "reset", at = @At("RETURN"))
    private void flywheel$resetStaticRevisions(CallbackInfo ci) {
        flywheel$staticRevisions.clear();
    }

    @Override
    public void flywheel$mergeStaticRevisions(Long2LongMap revisions) {
        for (var iterator = Long2LongMaps.fastIterator(revisions); iterator.hasNext(); ) {
            var revision = iterator.next();
            long key = revision.getLongKey();
            long section = key & ~15L;
            int kind = (int) (key & 15L);
            switch (kind) {
                case 0 -> {
                    flywheel$staticRevisions.remove(section | 2L);
                    flywheel$staticRevisions.remove(section | 3L);
                }
                case 2, 3 -> {
                    flywheel$staticRevisions.remove(section);
                    flywheel$staticRevisions.remove(section | (kind == 2 ? 3L : 2L));
                }
                case 1 -> {
                    flywheel$staticRevisions.remove(section | 4L);
                    flywheel$staticRevisions.remove(section | 5L);
                }
                case 4 -> {
                    flywheel$staticRevisions.remove(section | 1L);
                    flywheel$staticRevisions.remove(section | 5L);
                }
                case 5 -> {
                    flywheel$staticRevisions.remove(section | 1L);
                    flywheel$staticRevisions.remove(section | 4L);
                }
            }
            flywheel$staticRevisions.put(key, revision.getLongValue());
        }
    }

    @Override
    public Long2LongMap flywheel$takeStaticRevisions() {
        if (flywheel$staticRevisions.isEmpty()) return Long2LongMaps.EMPTY_MAP;
        var revisions = flywheel$staticRevisions;
        flywheel$staticRevisions = new Long2LongOpenHashMap();
        return revisions;
    }
}

#endif
