package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.GpuSimulation;

public record GpuPopulationKey(Object identity, GpuSimulation simulation, int capacity) {
    @Override
    public boolean equals(Object object) {
        return object instanceof GpuPopulationKey other
                && identity == other.identity
                && simulation.equals(other.simulation)
                && capacity == other.capacity;
    }

    @Override
    public int hashCode() {
        int hash = System.identityHashCode(identity);
        hash = 31 * hash + simulation.hashCode();
        return 31 * hash + capacity;
    }
}
