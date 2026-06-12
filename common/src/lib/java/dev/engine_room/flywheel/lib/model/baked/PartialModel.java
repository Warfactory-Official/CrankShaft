package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A handle to a JSON model not attached to any block or item; create partials at mod init, a late one stays empty until the next resource reload.
 */
public final class PartialModel {
    static final ConcurrentMap<Identifier, PartialModel> ALL = new ConcurrentHashMap<>();

    private final Identifier modelLocation;
    private final Object key;
    @Nullable
    private volatile BlockStateModel bakedModel;

    private PartialModel(Identifier modelLocation) {
        this.modelLocation = modelLocation;
        this.key = PartialModelController.INSTANCE.createKey(modelLocation);
    }

    public static PartialModel of(Identifier modelLocation) {
        return ALL.computeIfAbsent(modelLocation, PartialModel::new);
    }

    @ApiStatus.Internal
    public static Collection<PartialModel> all() {
        return ALL.values();
    }

    @Nullable
    public BlockStateModel get() {
        return bakedModel;
    }

    public Identifier modelLocation() {
        return modelLocation;
    }

    @ApiStatus.Internal
    public Object key() {
        return key;
    }

    @ApiStatus.Internal
    public void setBaked(@Nullable BlockStateModel bakedModel) {
        this.bakedModel = bakedModel;
    }
}
