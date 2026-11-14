package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import org.jspecify.annotations.Nullable;

public record InstancerKey<I extends Instance>(Environment environment, InstanceType<I> type, Model model, int bias,
                                               @Nullable GpuPopulationKey population) {
    public InstancerKey(Environment environment, InstanceType<I> type, Model model, int bias) {
        this(environment, type, model, bias, null);
    }
}
