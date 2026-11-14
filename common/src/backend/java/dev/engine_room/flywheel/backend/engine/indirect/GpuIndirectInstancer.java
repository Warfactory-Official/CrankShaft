package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.GpuPopulation;
import dev.engine_room.flywheel.api.instance.GpuSimulation;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.SlabFactory;
import dev.engine_room.flywheel.backend.engine.SlabPageCopier;
import org.jspecify.annotations.Nullable;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.PAGE_SIZE;

public final class GpuIndirectInstancer<I extends Instance> extends IndirectInstancer<I> {
    public final GpuSimulation simulation;
    public final int capacity;
    private GpuPopulationResources population;

    public GpuIndirectInstancer(InstancerKey<I> key, Recreate<I> recreate, SlabFactory slabFactory,
                                GpuSimulation simulation, int capacity) {
        super(key, recreate, slabFactory);
        this.simulation = simulation;
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public void setPopulation(GpuPopulation population) {
        this.population = (GpuPopulationResources) population;
    }

    public GpuPopulationResources population() {
        return population;
    }

    @Override
    public int instanceCount() {
        return population.deleted() ? 0 : capacity;
    }

    @Override
    public void update(int modelIndex, int baseInstance) {
        int pages = (capacity + PAGE_SIZE - 1) / PAGE_SIZE;
        boolean descriptorsChanged = modelIndex() != modelIndex || mapping.pageCount() != pages;
        updateIndices(modelIndex, baseInstance);
        if (descriptorsChanged) {
            mapping.updateCount(pages);
            for (int page = 0; page < pages; page++) {
                int slots = Math.min(PAGE_SIZE, capacity - page * PAGE_SIZE);
                int validBits = (int) ((1L << slots) - 1);
                mapping.updatePage(page, modelIndex, validBits);
            }
        }
    }

    @Override
    public void parallelUpdate() {
    }

    @Override
    public void uploadInstances(SlabPageCopier _) {
    }

    @Override
    void uploadInstances(SlabPageCopier _, @Nullable History _) {
    }

    @Override
    public void uploadInstances(SlabPageCopier _, boolean _) {
    }

    @Override
    public void clear() {
        if (population.deleted()) {
            population.close();
        }
    }

    @Override
    public void delete() {
        population.close();
        super.delete();
    }
}
