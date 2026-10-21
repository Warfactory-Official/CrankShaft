package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.GlobalEnvironment;

public record InstancerProviderImpl(EngineImpl engine, Environment environment) implements InstancerProvider {
    public InstancerProviderImpl(EngineImpl engine) {
        this(engine, GlobalEnvironment.INSTANCE);
    }

    @Override
    public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
        return engine.instancer(environment, type, model, bias);
    }
}
