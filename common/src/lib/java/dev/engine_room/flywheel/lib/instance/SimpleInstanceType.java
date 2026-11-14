package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceTransform;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.LongConsumer;

public final class SimpleInstanceType<I extends Instance> implements InstanceType<I> {
    private final Factory<I> factory;
    private final Layout layout;
    private final LongConsumer seed;
    private final Identifier vertexShader;
    private final Identifier cullShader;
    private final @Nullable InstanceTransform transform;
    private final boolean timeAnimated;

    public SimpleInstanceType(Factory<I> factory, Layout layout, LongConsumer seed, Identifier vertexShader,
                              Identifier cullShader) {
        this(factory, layout, seed, vertexShader, cullShader, null, false);
    }

    private SimpleInstanceType(Factory<I> factory, Layout layout, LongConsumer seed, Identifier vertexShader,
                               Identifier cullShader, @Nullable InstanceTransform transform, boolean timeAnimated) {
        this.factory = factory;
        this.layout = layout;
        this.seed = seed;
        this.vertexShader = vertexShader;
        this.cullShader = cullShader;
        this.transform = transform;
        this.timeAnimated = timeAnimated;
        // Port: expose downstream layouts before loading-time uber shader compilation.
        ShaderWarmupRegistry.register(this);
    }

    public static <I extends Instance> Builder<I> builder(Factory<I> factory) {
        return new Builder<>(factory);
    }

    @Override
    public I create(InstanceHandle handle) {
        return factory.create(this, handle);
    }

    @Override
    public Layout layout() {
        return layout;
    }

    @Override
    public LongConsumer seed() {
        return seed;
    }

    @Override
    public Identifier vertexShader() {
        return vertexShader;
    }

    @Override
    public Identifier cullShader() {
        return cullShader;
    }

    @Override
    public @Nullable InstanceTransform transform() {
        return transform;
    }

    @Override
    public boolean timeAnimated() {
        return timeAnimated;
    }

    @FunctionalInterface
    public interface Factory<I extends Instance> {
        I create(InstanceType<I> type, InstanceHandle handle);
    }

    public static final class Builder<I extends Instance> {
        private final Factory<I> factory;
        private Layout layout;
        private LongConsumer seed;
        private Identifier vertexShader;
        private Identifier cullShader;
        private @Nullable InstanceTransform transform;
        private boolean timeAnimated;

        public Builder(Factory<I> factory) {
            this.factory = factory;
        }

        public Builder<I> layout(Layout layout) {
            this.layout = layout;
            return this;
        }

        public Builder<I> seed(LongConsumer seed) {
            this.seed = seed;
            return this;
        }

        public Builder<I> vertexShader(Identifier vertexShader) {
            this.vertexShader = vertexShader;
            return this;
        }

        public Builder<I> cullShader(Identifier cullShader) {
            this.cullShader = cullShader;
            return this;
        }

        public Builder<I> transform(InstanceTransform transform) {
            this.transform = transform;
            return this;
        }

        /**
         * {@link InstanceType#timeAnimated()}.
         */
        public Builder<I> timeAnimated() {
            this.timeAnimated = true;
            return this;
        }

        public SimpleInstanceType<I> build() {
            Objects.requireNonNull(layout);
            Objects.requireNonNull(seed);
            Objects.requireNonNull(vertexShader);
            Objects.requireNonNull(cullShader);

            return new SimpleInstanceType<>(factory, layout, seed, vertexShader, cullShader, transform, timeAnimated);
        }
    }
}
