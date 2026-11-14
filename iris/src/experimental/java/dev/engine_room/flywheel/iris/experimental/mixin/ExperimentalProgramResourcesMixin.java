package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.engine_room.flywheel.iris.experimental.ExternalStorageBindings;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.ProgramResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;

@Mixin(ProgramResources.class)
abstract class ExperimentalProgramResourcesMixin implements ExternalStorageBindings {
    @Shadow
    @Final
    private List<ShaderResourceLayout.Descriptor> storageDeclarations;
    @Unique
    private List<ShaderResourceLayout.Descriptor> flywheel$packStorage;

    @Override
    public void flywheel$externalStorage(Set<String> names) {
        flywheel$packStorage = storageDeclarations.stream().filter(d -> !names.contains(d.name())).toList();
    }

    // Retain declarations for Vulkan access tracking; skip only pack-buffer lookup.
    @ModifyExpressionValue(method = "getBufferBindings", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/samplers/ProgramResources;storageDeclarations:Ljava/util/List;"))
    private List<ShaderResourceLayout.Descriptor> flywheel$packStorage(List<ShaderResourceLayout.Descriptor> original) {
        return flywheel$packStorage == null ? original : flywheel$packStorage;
    }
}
