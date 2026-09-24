package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

@Mixin(EntityRenderers.class)
abstract class EmfEntityRenderersMixin {
    // Around each type's construction, inside EMF's own per-type naming context.
    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private static void flywheel$recordEmfRoots(Map<EntityType<?>, EntityRendererProvider<?>> providers,
                                                BiConsumer<EntityType<?>, EntityRendererProvider<?>> create,
                                                Operation<Void> original) {
        Set<EntityType<?>> restyled = new HashSet<>();
        original.call(providers, (BiConsumer<EntityType<?>, EntityRendererProvider<?>>) (type, provider) -> {
            if (EntityFeatureCompat.bakesEmfRoot(() -> create.accept(type, provider))) {
                restyled.add(type);
            }
        });
        EntityFeatureCompat.emfRestyled(restyled);
    }
}
