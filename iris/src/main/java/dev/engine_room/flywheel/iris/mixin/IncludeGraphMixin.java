package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;

@Mixin(IncludeGraph.class)
abstract class IncludeGraphMixin {
    @WrapOperation(method = "<init>(Ljava/nio/file/Path;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/shaderpack/include/IncludeGraph;readFile(Ljava/nio/file/Path;)Ljava/lang/String;"))
    private String flywheel$patchedSource(Path path, Operation<String> original) {
        String patched = ContractPatches.override(path);
        return patched != null ? patched : original.call(path);
    }
}
