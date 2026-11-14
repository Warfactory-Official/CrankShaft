package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.GpuPopulation;

public interface GpuPopulationResources extends GpuPopulation {
    void close();
}
