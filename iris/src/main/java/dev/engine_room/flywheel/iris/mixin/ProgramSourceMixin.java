package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ProgramSource.class)
abstract class ProgramSourceMixin {
    @Shadow
    @Final
    private String name;
    @Shadow
    @Final
    private ProgramSet parent;

    // Original sources remain visible while ProgramSet discovers render-target formats and program directives.
    @Inject(method = "getFragmentSource", at = @At("RETURN"), cancellable = true)
    private void flywheel$adaptDeferred(CallbackInfoReturnable<Optional<String>> cir) {
        String patched = ((ContractProgramSet) parent).flywheel$patchedFragment(name);
        if (patched != null) cir.setReturnValue(Optional.of(patched));
    }
}
