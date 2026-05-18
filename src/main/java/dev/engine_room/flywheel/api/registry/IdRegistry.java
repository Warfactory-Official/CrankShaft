package dev.engine_room.flywheel.api.registry;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnmodifiableView;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

@ApiStatus.NonExtendable
public interface IdRegistry<T> extends Iterable<T> {
    void register(ResourceLocation id, T object);

    <S extends T> S registerAndGet(ResourceLocation id, S object);

    @Nullable
    T get(ResourceLocation id);

    @Nullable
    ResourceLocation getId(T object);

    T getOrThrow(ResourceLocation id);

    ResourceLocation getIdOrThrow(T object);

    @UnmodifiableView
    Set<ResourceLocation> getAllIds();

    @UnmodifiableView
    Collection<T> getAll();

    boolean isFrozen();
}
