package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

@Mixin(EMFManager.class)
abstract class EmfManagerMixin {
    // Every EMF model substitution.
    @Inject(method = "injectIntoModelRootGetter", at = @At("RETURN"))
    private void flywheel$rootBaked(ModelLayerLocation layer, ModelPart root, CallbackInfoReturnable<ModelPart> cir) {
        if (cir.getReturnValue() instanceof EMFModelPartRoot) {
            EntityFeatureCompat.emfRootBaked();
        }
    }
}
