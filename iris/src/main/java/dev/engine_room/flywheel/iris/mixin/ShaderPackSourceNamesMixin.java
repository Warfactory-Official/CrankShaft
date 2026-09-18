package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.ContractProgram;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Only listed starts enter the include graph; ProgramSetMixin reads them.
@Mixin(ShaderPackSourceNames.class)
abstract class ShaderPackSourceNamesMixin {
    @Shadow
    private static void addStarts(ImmutableList.Builder<String> potentialFileNames, String baseName) {
        throw new AssertionError();
    }

    @Inject(method = "findPotentialStarts", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList$Builder;build()Lcom/google/common/collect/ImmutableList;"))
    private static void flywheel$addContractStarts(CallbackInfoReturnable<ImmutableList<String>> cir,
                                                   @Local ImmutableList.Builder<String> potentialFileNames) {
        for (ContractProgram program : ContractProgram.values()) {
            addStarts(potentialFileNames, program.sourceName);
        }
    }
}
