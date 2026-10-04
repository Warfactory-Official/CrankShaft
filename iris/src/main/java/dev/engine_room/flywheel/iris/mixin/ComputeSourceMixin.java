package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ComputeSource.class)
abstract class ComputeSourceMixin {
    @Shadow
    @Final
    private String name;
    @Shadow
    @Final
    private ProgramSet parent;

    // Keep work-group/directive discovery on the original source, as for graphics stages.
    @Inject(method = "getSource", at = @At("RETURN"), cancellable = true)
    private void flywheel$adaptCompute(CallbackInfoReturnable<Optional<String>> cir) {
        String source = ((ContractProgramSet) parent).flywheel$patchedFragment(name + ".csh");
        if (source != null) cir.setReturnValue(Optional.of(source));
    }
}
