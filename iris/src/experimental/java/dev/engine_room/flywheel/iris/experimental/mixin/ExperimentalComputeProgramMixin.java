package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.experimental.ComputeIndirectDispatch;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ComputeProgram.class)
abstract class ExperimentalComputeProgramMixin implements ComputeIndirectDispatch {
    @Shadow
    private ComputePipeline pipeline;

    @Override
    public void flywheel$dispatchIndirect(FilledIndirectPointer command, long tick, long frame) {
        var program = (ComputeProgram) (Object) this;
        program.prepare();
        program.getUniforms().update(tick, frame);
        Iris.getRenderBackend().commands().dispatch(pipeline, new Vector3i(), command);
    }
}
