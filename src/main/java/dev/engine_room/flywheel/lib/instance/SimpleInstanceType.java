package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.function.LongConsumer;

public final class SimpleInstanceType<I extends Instance> implements InstanceType<I> {
    private final Factory<I> factory;
    private final Layout layout;
    private final LongConsumer seed;
    private final ResourceLocation vertexShader;
    private final ResourceLocation cullShader;

    public SimpleInstanceType(Factory<I> factory, Layout layout, LongConsumer seed, ResourceLocation vertexShader, ResourceLocation cullShader) {
        this.factory = factory;
        this.layout = layout;
        this.seed = seed;
        this.vertexShader = vertexShader;
        this.cullShader = cullShader;
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
    public ResourceLocation vertexShader() {
        return vertexShader;
    }

    @Override
    public ResourceLocation cullShader() {
        return cullShader;
    }

    @FunctionalInterface
    public interface Factory<I extends Instance> {
        I create(InstanceType<I> type, InstanceHandle handle);
    }

    public static final class Builder<I extends Instance> {
        private final Factory<I> factory;
        private Layout layout;
        private LongConsumer seed;
        private ResourceLocation vertexShader;
        private ResourceLocation cullShader;

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

        public Builder<I> vertexShader(ResourceLocation vertexShader) {
            this.vertexShader = vertexShader;
            return this;
        }

        public Builder<I> cullShader(ResourceLocation cullShader) {
            this.cullShader = cullShader;
            return this;
        }

        public SimpleInstanceType<I> build() {
            Objects.requireNonNull(layout);
            Objects.requireNonNull(seed);
            Objects.requireNonNull(vertexShader);
            Objects.requireNonNull(cullShader);

            return new SimpleInstanceType<>(factory, layout, seed, vertexShader, cullShader);
        }
    }
}
