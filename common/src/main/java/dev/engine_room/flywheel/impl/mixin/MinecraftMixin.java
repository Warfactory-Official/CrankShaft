package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void flw$warmVisualShaders(CallbackInfo ci) {
        try {
            for (;;) {
                try {
                    ShaderWarmup.warm();
                    break;
                } catch (ProgramAvailability.Failure failure) {
                    if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                    if (!ProgramAvailability.allows(failure.feature())) {
                        BackendManagerImpl.recover(failure);
                    } else {
                        ProgramAvailability.reject(failure.feature(), failure);
                        ShaderWarmup.retry(failure.feature());
                        BackendManagerImpl.reselect();
                    }
                } catch (BackendUnavailableException failure) {
                    if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                    BackendManagerImpl.recover(failure);
                }
            }
        } catch (Throwable e) {
            ((Minecraft) (Object) this).delayCrash(CrashReport.forThrowable(e, "Warming CrankShaft shaders"));
        }
    }
}
