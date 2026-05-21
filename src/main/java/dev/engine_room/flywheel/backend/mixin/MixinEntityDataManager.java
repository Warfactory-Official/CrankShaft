package dev.engine_room.flywheel.backend.mixin;

import com.google.common.collect.Lists;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// Vanilla routes every synced-flag read (isInvisible, isChild, ...) through a locked
// HashMap<Integer, DataEntry> lookup; at high entity counts the boxing+hashing dominates the
// render path. Ids are capped at 254 and assigned densely per class chain, so storage becomes a
// copy-on-write flat array: structural writes (registration) copy under a writer mutex, every
// read path works on an atomic snapshot from one reference load — no read locks anywhere. Value
// mutations stay in-place on stable DataEntry objects, as in vanilla. The vanilla map is nulled
// out in <init> (anything still reaching for it fails loudly) and the lock collapses to a shared
// dead instance.
@Mixin(EntityDataManager.class)
public abstract class MixinEntityDataManager {

    @Shadow
    @Final
    private Entity entity;
    @Shadow
    private boolean empty;
    @Shadow
    private boolean dirty;

    @Shadow
    protected abstract <T> void setEntryValue(EntityDataManager.DataEntry<T> target, EntityDataManager.DataEntry<?> source);

    @Shadow
    private static <T> void writeEntry(PacketBuffer buf, EntityDataManager.DataEntry<T> entry) throws IOException {
        throw new AssertionError();
    }

    // volatile so a (nonstandard) post-construction registration still publishes safely; on the
    // hot read path this is a plain load. Seeded by flw$init — mixin instance-field initialisers
    // don't reliably merge into target ctors.
    @Unique
    private volatile EntityDataManager.DataEntry<?>[] flw$byId;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flw$init(Entity entityIn, CallbackInfo ci) {
        flw$byId = new EntityDataManager.DataEntry<?>[0];
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/Maps;newHashMap()Ljava/util/HashMap;"))
    private static HashMap<?, ?> flw$noMap() {
        return null;
    }

    // Mixin forbids null from a constructor redirect (unlike the INVOKE redirect above), so every
    // manager shares one dead instance instead.
    @Unique
    private static final ReentrantReadWriteLock FLW$DEAD_LOCK = new ReentrantReadWriteLock();

    @Redirect(method = "<init>", at = @At(value = "NEW",
            target = "()Ljava/util/concurrent/locks/ReentrantReadWriteLock;"))
    private static ReentrantReadWriteLock flw$noLock() {
        return FLW$DEAD_LOCK;
    }

    @Unique
    private void flw$put(EntityDataManager.DataEntry<?> entry) {
        int id = entry.getKey().getId();
        synchronized (this) {
            EntityDataManager.DataEntry<?>[] byId = Arrays.copyOf(flw$byId, Math.max(flw$byId.length, id + 1));
            byId[id] = entry;
            flw$byId = byId;
        }
        this.empty = false;
    }

    /**
     * @author movblock
     * @reason duplicate check against the flat array instead of the removed map.
     */
    @Overwrite
    public <T> void register(DataParameter<T> key, T value) {
        int i = key.getId();
        if (i > 254) {
            throw new IllegalArgumentException("Data value id is too big with " + i + "! (Max is 254)");
        } else if (i < flw$byId.length && flw$byId[i] != null) {
            throw new IllegalArgumentException("Duplicate id value for " + i + "!");
        } else if (DataSerializers.getSerializerId(key.getSerializer()) < 0) {
            throw new IllegalArgumentException("Unregistered serializer " + key.getSerializer() + " for " + i + "!");
        } else {
            flw$put(new EntityDataManager.DataEntry<>(key, value));
        }
    }

    /**
     * @author movblock
     * @reason redirect to the copy-on-write array store. Only register() calls this in vanilla;
     * kept functional for reflective callers.
     */
    @Overwrite
    private <T> void setEntry(DataParameter<T> key, T value) {
        flw$put(new EntityDataManager.DataEntry<>(key, value));
    }

    /**
     * @author movblock
     * @reason the hot path: one array read, no lock, no boxing. Drops vanilla's crash-report
     * wrapping — an unregistered key fails fast on the array/null deref.
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    private <T> EntityDataManager.DataEntry<T> getEntry(DataParameter<T> key) {
        return (EntityDataManager.DataEntry<T>) flw$byId[key.getId()];
    }

    /**
     * @author movblock
     * @reason iterate the array snapshot lock-free.
     */
    @Overwrite
    @Nullable
    public List<EntityDataManager.DataEntry<?>> getDirty() {
        List<EntityDataManager.DataEntry<?>> list = null;
        if (this.dirty) {
            for (EntityDataManager.DataEntry<?> entry : flw$byId) {
                if (entry != null && entry.isDirty()) {
                    entry.setDirty(false);
                    if (list == null) {
                        list = Lists.newArrayList();
                    }
                    list.add(entry.copy());
                }
            }
        }
        this.dirty = false;
        return list;
    }

    /**
     * @author movblock
     * @reason iterate the array snapshot lock-free.
     */
    @Overwrite
    public void writeEntries(PacketBuffer buf) throws IOException {
        for (EntityDataManager.DataEntry<?> entry : flw$byId) {
            if (entry != null) {
                writeEntry(buf, entry);
            }
        }
        buf.writeByte(255);
    }

    /**
     * @author movblock
     * @reason iterate the array snapshot lock-free.
     */
    @Overwrite
    @Nullable
    public List<EntityDataManager.DataEntry<?>> getAll() {
        List<EntityDataManager.DataEntry<?>> list = null;
        for (EntityDataManager.DataEntry<?> entry : flw$byId) {
            if (entry != null) {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(entry.copy());
            }
        }
        return list;
    }

    /**
     * @author movblock
     * @reason apply packet values via array lookup; runs on the main thread (packet handlers are
     * rescheduled), so value writes need no lock.
     */
    @Overwrite
    public void setEntryValues(List<EntityDataManager.DataEntry<?>> entriesIn) {
        EntityDataManager.DataEntry<?>[] byId = flw$byId;
        for (EntityDataManager.DataEntry<?> source : entriesIn) {
            int id = source.getKey().getId();
            EntityDataManager.DataEntry<?> target = id < byId.length ? byId[id] : null;
            if (target != null) {
                setEntryValue(target, source);
                this.entity.notifyDataManagerChange(source.getKey());
            }
        }
        this.dirty = true;
    }

    /**
     * @author movblock
     * @reason iterate the array snapshot lock-free.
     */
    @Overwrite
    public void setClean() {
        this.dirty = false;
        for (EntityDataManager.DataEntry<?> entry : flw$byId) {
            if (entry != null) {
                entry.setDirty(false);
            }
        }
    }
}
