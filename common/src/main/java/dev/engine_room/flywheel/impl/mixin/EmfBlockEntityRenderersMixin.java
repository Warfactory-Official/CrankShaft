package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

@Mixin(BlockEntityRenderers.class)
abstract class EmfBlockEntityRenderersMixin {
    // = EmfEntityRenderersMixin
    @WrapOperation(method = "createEntityRenderers", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private static void flywheel$recordEmfRoots(Map<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> providers,
                                                BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> create,
                                                Operation<Void> original) {
        Set<BlockEntityType<?>> restyled = new HashSet<>();
        original.call(providers, (BiConsumer<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>>) (type, provider) -> {
            if (EntityFeatureCompat.bakesEmfRoot(() -> create.accept(type, provider))) {
                restyled.add(type);
            }
        });
        EntityFeatureCompat.emfRestyledBlockEntities(restyled);
    }
}
