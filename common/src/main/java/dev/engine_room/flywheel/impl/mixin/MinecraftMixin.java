package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.compile.ShaderWarmupSplash;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin extends ReentrantBlockableEventLoop<Runnable> {

    public MinecraftMixin(String name, boolean propagatesCrashes) {
        super(name, propagatesCrashes);
    }

    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void flywheel$warmVisualShaders(CallbackInfo _) {
        try {
            ShaderWarmupSplash.run(() -> BackendManagerImpl.warmWithRecovery(ShaderWarmup::warm));
        } catch (ShaderWarmupSplash.CancellationException cancelled) {
            FlwBackend.LOGGER.info(cancelled.getMessage());
        } catch (Throwable e) {
            delayCrash(CrashReport.forThrowable(e, "Warming CrankShaft shaders"));
        } finally {
            ShaderWarmup.reloaded();
        }
    }

    // A failed recovery reload ends without onResourceLoadFinished; engines draw again, compiling on demand.
    @Inject(method = "abortResourcePackRecovery", at = @At("HEAD"))
    private void flywheel$endFailedReload(CallbackInfo _) {
        ShaderWarmup.reloaded();
    }
}
