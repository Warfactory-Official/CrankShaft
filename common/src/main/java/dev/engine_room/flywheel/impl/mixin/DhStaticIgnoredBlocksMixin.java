package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockStateWrapper.class)
abstract class DhStaticIgnoredBlocksMixin {
    // DH expands registry IDs to every state once while constructing its native cached render-ignore set.
    @WrapOperation(method = "getRendererIgnoredBlocks", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/common/wrappers/block/BlockStateWrapper;getAllBlockWrappers(Lcom/seibel/distanthorizons/core/config/types/ConfigEntry;Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;)Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;"))
    private static ObjectOpenHashSet<IBlockStateWrapper> flywheel$ignoreStaticContainers(
            ConfigEntry<String> config, ObjectOpenHashSet<String> baseIds, ILevelWrapper level,
            Operation<ObjectOpenHashSet<IBlockStateWrapper>> original) {
        if (DistantHorizonsCompat.ignoreStaticContainers()) {
            for (var block : BuiltInRegistries.BLOCK) {
                if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock) {
                    baseIds.add(BuiltInRegistries.BLOCK.getKey(block).toString());
                }
            }
        }
        return original.call(config, baseIds, level);
    }
}
