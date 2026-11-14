package dev.engine_room.flywheel.impl.far;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import dev.engine_room.flywheel.api.visualization.FarVisualRegistry;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;

/**
 * Reads native-resolution placements from the current DH level's accepted local data.
 */
public final class DhFarSource implements FarTerrainSource {
    private static final int PAGE_SHIFT = 6;
    private static final Map<FullDataSourceProviderV2, ReadLifetime> READ_LIFETIMES = new WeakHashMap<>();

    private final ClientLevel level;
    private final IClientLevelWrapper wrapper;
    private final IDhLevel dhLevel;
    private final FullDataSourceProviderV2 provider;
    private final Executor executor;
    private final ReadLifetime lifetime;
    private final int minY;
    private final Set<Long> changes = ConcurrentHashMap.newKeySet();
    private final IDataSourceUpdateListenerFunc<FullDataSourceV2> updateListener = this::dataChanged;
    private volatile boolean closed;

    public static @Nullable DhFarSource open(ClientLevel level) {
        var wrapper = ClientLevelWrapper.getWrapper(level);
        if (wrapper == null) {
            return null;
        }
        var dhLevel = wrapper.getDhLevel();
        var executor = ThreadPoolUtil.getFileHandlerExecutor();
        if (dhLevel == null || executor == null) {
            return null;
        }
        var provider = dhLevel.getFullDataProvider();
        var lifetime = lifetime(provider);
        if (lifetime.closing) {
            return null;
        }
        return new DhFarSource(level, wrapper, dhLevel, provider, executor, lifetime);
    }

    private DhFarSource(ClientLevel level, IClientLevelWrapper wrapper, IDhLevel dhLevel,
                        FullDataSourceProviderV2 provider, Executor executor, ReadLifetime lifetime) {
        this.level = level;
        this.wrapper = wrapper;
        this.dhLevel = dhLevel;
        this.provider = provider;
        this.executor = executor;
        this.lifetime = lifetime;
        minY = wrapper.getMinHeight();
        provider.addDataSourceUpdateListener(updateListener);
    }

    private static synchronized ReadLifetime lifetime(FullDataSourceProviderV2 provider) {
        return READ_LIFETIMES.computeIfAbsent(provider, _ -> new ReadLifetime());
    }

    /**
     * Called before DH closes its provider so active reads finish before the database is released.
     */
    public static void providerClosing(FullDataSourceProviderV2 provider) {
        var lifetime = lifetime(provider);
        lifetime.closing = true;
        var writeLock = lifetime.lock.writeLock();
        writeLock.lock();
        writeLock.unlock();
    }

    @Override
    public int pageShift() {
        return PAGE_SHIFT;
    }

    @Override
    public CompletableFuture<@Nullable FarTerrainPage> read(int pageX, int pageZ) {
        try {
            return CompletableFuture.supplyAsync(() -> readPage(pageX, pageZ), executor);
        } catch (RejectedExecutionException _) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private @Nullable FarTerrainPage readPage(int pageX, int pageZ) {
        var readLock = lifetime.lock.readLock();
        readLock.lock();
        try {
            if (closed || lifetime.closing) {
                return null;
            }
            long position = DhSectionPos.encode(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, pageX, pageZ);
            // RemoteFullDataSourceProvider.get() requests network updates. Reading its repository directly
            // leaves stream scheduling with DH and preserves the distinction between absent and empty data.
            try (var dto = provider.repo.getByKey(position)) {
                if (dto == null) {
                    return null;
                }
                try (var data = dto.createDataSource(wrapper, null)) {
                    var page = decode(data, pageX, pageZ);
                    if (closed || lifetime.closing) {
                        return null;
                    }
                    return page;
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException | DataCorruptedException e) {
                throw new CompletionException(e);
            }
        } finally {
            readLock.unlock();
        }
    }

    private FarTerrainPage decode(FullDataSourceV2 data, int pageX, int pageZ) {
        var selectedStates = new BlockState[data.mapping.size()];
        boolean hasProviders = false;
        for (int id = 0; id < selectedStates.length; id++) {
            var wrapper = data.mapping.getBlockStateWrapper(id);
            if (wrapper.isAir()) continue;
            var state = (BlockState) wrapper.getWrappedMcObject();
            if (FarVisualRegistry.get(state) != null) {
                selectedStates[id] = state;
                hasProviders = true;
            }
        }

        var positions = new LongArrayList();
        var states = new ObjectArrayList<BlockState>();
        var lights = new IntArrayList();
        if (hasProviders) {
            int baseX = pageX << PAGE_SHIFT;
            int baseZ = pageZ << PAGE_SHIFT;
            for (int x = 0; x < FullDataSourceV2.WIDTH; x++) {
                for (int z = 0; z < FullDataSourceV2.WIDTH; z++) {
                    var column = data.getColumnAtRelPos(x, z);
                    for (int run = 0; run < column.size(); run++) {
                        long point = column.getLong(run);
                        if (point == FullDataPointUtil.EMPTY_DATA_POINT) {
                            continue;
                        }
                        var state = selectedStates[FullDataPointUtil.getId(point)];
                        if (state == null) {
                            continue;
                        }
                        int bottom = minY + FullDataPointUtil.getBottomY(point);
                        int top = bottom + FullDataPointUtil.getHeight(point);
                        int light = FullDataPointUtil.getBlockLight(point) << 4
                                | FullDataPointUtil.getSkyLight(point) << 20;
                        for (int y = bottom; y < top; y++) {
                            positions.add(BlockPos.asLong(baseX + x, y, baseZ + z));
                            states.add(state);
                            lights.add(light);
                        }
                    }
                }
            }
        }
        return new FarTerrainPage(pageX, pageZ, positions.toLongArray(), states.toArray(BlockState[]::new),
                lights.toIntArray());
    }

    private void dataChanged(FullDataSourceV2 data) {
        long position = data.getPos();
        if (!closed && DhSectionPos.getDetailLevel(position) == DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL) {
            changes.add(ChunkPos.pack(DhSectionPos.getX(position), DhSectionPos.getZ(position)));
        }
    }

    @Override
    public void drainChanges(LongConsumer consumer) {
        for (long position : changes) {
            if (changes.remove(position)) {
                consumer.accept(position);
            }
        }
    }

    @Override
    public boolean valid(ClientLevel level) {
        return !closed && !lifetime.closing && this.level == level
                && ClientLevelWrapper.getWrapper(level) == wrapper
                && wrapper.getDhLevel() == dhLevel
                && dhLevel.getFullDataProvider() == provider;
    }

    @Override
    public void close() {
        closed = true;
        provider.removeDataSourceUpdateListener(updateListener);
        changes.clear();
    }

    private static final class ReadLifetime {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        volatile boolean closing;
    }
}
