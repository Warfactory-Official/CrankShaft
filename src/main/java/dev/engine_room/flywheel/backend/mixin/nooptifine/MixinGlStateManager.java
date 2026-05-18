package dev.engine_room.flywheel.backend.mixin.nooptifine;

import net.minecraft.client.renderer.GlStateManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(GlStateManager.class)
public abstract class MixinGlStateManager {
    // 1.12.2: vanilla GlStateManager pre-sizes its textureState array for 8 units; Flywheel binds
    // its own samplers above that (Samplers.NOISE = T9), so widen the cache to 12 to keep vanilla's
    // state tracker in sync with the units we touch. Optifine bumps this to 32 so it isn't needed.
    @ModifyConstant(
            method = "<clinit>",
            constant = @Constant(intValue = 8),
            require = 2,
            allow = 2,
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/GlStateManager;normalizeState:Lnet/minecraft/client/renderer/GlStateManager$BooleanState;",
                            opcode = Opcodes.PUTSTATIC
                    )
            )
    )
    private static int flw$enlargeTextureCache(int original) {
        return 12;
    }
}
