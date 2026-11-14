package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.impl.FlwConfig;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Config.class)
abstract class SodiumConfigApplyMixin {
    // Sodium flushes storage before processing renderer flags. Validation writes outside Apply stay immediate.
    @WrapMethod(method = "applyAllOptions")
    private void flywheel$batchPersistence(Operation<Void> original) {
        var config = FlwConfig.INSTANCE;
        config.beginBatch();
        try {
            original.call();
        } finally {
            // Input task failures can be caught by Minecraft's event loop while the screen stays alive.
            config.endBatch(false);
        }
    }
}
