package dev.engine_room.flywheel.lib.backend;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.Engine;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

public final class SimpleBackend implements Backend {
    private final Function<World, Engine> engineFactory;
    private final IntSupplier priority;
    private final BooleanSupplier isSupported;

    public SimpleBackend(Function<World, Engine> engineFactory, IntSupplier priority, BooleanSupplier isSupported) {
        this.engineFactory = engineFactory;
        this.priority = priority;
        this.isSupported = isSupported;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Engine createEngine(World level) {
        return engineFactory.apply(level);
    }

    @Override
    public int priority() {
        return priority.getAsInt();
    }

    @Override
    public boolean isSupported() {
        return isSupported.getAsBoolean();
    }

    public static final class Builder {
        @Nullable
        private Function<World, Engine> engineFactory;
        private IntSupplier priority = () -> 0;
        @Nullable
        private BooleanSupplier isSupported;

        public Builder engineFactory(Function<World, Engine> engineFactory) {
            this.engineFactory = engineFactory;
            return this;
        }

        public Builder priority(int priority) {
            return priority(() -> priority);
        }

        public Builder priority(IntSupplier priority) {
            this.priority = priority;
            return this;
        }

        public Builder supported(BooleanSupplier isSupported) {
            this.isSupported = isSupported;
            return this;
        }

        public Backend register(ResourceLocation id) {
            Objects.requireNonNull(engineFactory);
            Objects.requireNonNull(isSupported);

            return Backend.REGISTRY.registerAndGet(id, new SimpleBackend(engineFactory, priority, isSupported));
        }
    }
}
