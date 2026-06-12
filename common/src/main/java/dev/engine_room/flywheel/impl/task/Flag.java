package dev.engine_room.flywheel.impl.task;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A flag that can be raised and lowered in a thread-safe fashion, useful with {@link RaisePlan}.
 */
public final class Flag {
    private final AtomicBoolean raised = new AtomicBoolean(false);
    @Nullable
    private final String name;

    public Flag(@Nullable String name) {
        this.name = name;
    }

    public Flag() {
        this(null);
    }

    /**
     * Raise this flag, indicating a key point in execution.
     */
    public void raise() {
        raised.set(true);
    }

    /**
     * Lower this flag, if it was previously raised.
     */
    public void lower() {
        raised.set(false);
    }

    public boolean isRaised() {
        return raised.get();
    }

    public boolean isLowered() {
        return !isRaised();
    }

    @Nullable
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "Flag[name=" + name + ']';
    }
}
