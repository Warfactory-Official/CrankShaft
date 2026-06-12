package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.MeshPool;

import java.util.ArrayList;
import java.util.List;

public class IndirectCullingGroup<I extends Instance> {
    final List<IndirectDraw> indirectDraws = new ArrayList<>();
    private final InstanceType<I> instanceType;
    private final List<IndirectInstancer<I>> instancers = new ArrayList<>();
    boolean drawsDirty;

    IndirectCullingGroup(InstanceType<I> instanceType) {
        this.instanceType = instanceType;
    }

    public boolean flushInstancers() {
        for (var iterator = instancers.iterator(); iterator.hasNext(); ) {
            var instancer = iterator.next();

            if (instancer.instanceCount() == 0) {
                iterator.remove();
                instancer.delete();
            }
        }

        if (indirectDraws.removeIf(IndirectDraw::deleted)) {
            drawsDirty = true;
        }

        return indirectDraws.isEmpty();
    }

    List<IndirectInstancer<I>> instancers() {
        return instancers;
    }

    InstanceType<I> instanceType() {
        return instanceType;
    }

    public void add(IndirectInstancer<I> instancer, InstancerKey<I> key, MeshPool meshPool,
                    ObjectStorage objectStorage) {
        instancer.mapping = objectStorage.createMapping(instanceType);
        instancer.update(instancers.size(), -1);

        instancers.add(instancer);

        List<Model.ConfiguredMesh> meshes = key.model()
                                               .meshes();
        for (int i = 0; i < meshes.size(); i++) {
            var entry = meshes.get(i);

            MeshPool.PooledMesh mesh = meshPool.alloc(entry.mesh());
            var draw = new IndirectDraw(instancer, entry.material(), mesh, key.bias(), i);
            indirectDraws.add(draw);
            instancer.addDraw(draw);
            warmUp(entry.material());
        }

        drawsDirty = true;
    }

    private void warmUp(Material material) {
        if (material.transparency() == Transparency.ORDER_INDEPENDENT) {
            OitPipelines.uberProducer(material, OitMode.DEPTH_RANGE);
            OitPipelines.uberProducer(material, OitMode.GENERATE_COEFFICIENTS);
            OitPipelines.uberProducer(material, OitMode.EVALUATE);
        } else {
            IndirectPipeline.uberPipelineFor(material);
        }
    }

    long writeModels(long writePtr) {
        for (var model : instancers) {
            model.writeModel(writePtr);
            writePtr += IndirectBuffers.MODEL_STRIDE;
        }
        return writePtr;
    }
}
