package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.GpuPopulation;
import dev.engine_room.flywheel.api.instance.GpuSimulation;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.embed.Environment;

public record InstancerProviderImpl(EngineImpl engine, Environment environment) implements InstancerProvider {
    @Override
    public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
        return engine.instancer(environment, type, model, bias);
    }

    @Override
    public boolean supportsGpuSimulation() {
        return engine.supportsGpuSimulation();
    }

    @Override
    public GpuPopulation gpuPopulation(Object identity, InstanceType<?> type, Model model, GpuSimulation simulation,
                                       int capacity, int bias) {
        return engine.gpuPopulation(environment, identity, type, model, simulation, capacity, bias);
    }
}
