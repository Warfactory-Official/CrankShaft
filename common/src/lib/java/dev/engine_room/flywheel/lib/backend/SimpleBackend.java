package dev.engine_room.flywheel.lib.backend;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.Engine;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

public final class SimpleBackend implements Backend {
    private final Function<LevelAccessor, Engine> engineFactory;
    private final IntSupplier priority;
    private final BooleanSupplier isSupported;
    private final boolean gpuDriven;

    public SimpleBackend(Function<LevelAccessor, Engine> engineFactory, IntSupplier priority,
                         BooleanSupplier isSupported, boolean gpuDriven) {
        this.engineFactory = engineFactory;
        this.priority = priority;
        this.isSupported = isSupported;
        this.gpuDriven = gpuDriven;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Engine createEngine(LevelAccessor level) {
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

    @Override
    public boolean isGpuDriven() {
        return gpuDriven;
    }

    public static final class Builder {
        @Nullable
        private Function<LevelAccessor, Engine> engineFactory;
        private IntSupplier priority = () -> 0;
        @Nullable
        private BooleanSupplier isSupported;
        private boolean gpuDriven = false;

        public Builder engineFactory(Function<LevelAccessor, Engine> engineFactory) {
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

        /**
         * Mark this backend as GPU-driven (compute-culled + GPU-built draw). See {@link Backend#isGpuDriven()}.
         */
        public Builder gpuDriven(boolean gpuDriven) {
            this.gpuDriven = gpuDriven;
            return this;
        }

        public Backend register(Identifier id) {
            Objects.requireNonNull(engineFactory);
            Objects.requireNonNull(isSupported);

            return Backend.REGISTRY.registerAndGet(id,
                    new SimpleBackend(engineFactory, priority, isSupported, gpuDriven));
        }
    }
}
