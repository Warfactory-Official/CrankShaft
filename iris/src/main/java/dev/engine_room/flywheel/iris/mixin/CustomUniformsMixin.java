package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.GuestUniforms;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Mixin(CustomUniforms.class)
abstract class CustomUniformsMixin implements GuestUniforms {
    @Shadow
    @Final
    private List<CachedUniform> uniformOrder;

    @Shadow
    public abstract void assignTo(LocationalUniformHolder holder);

    @Shadow
    public abstract void optimise();

    @Unique
    private List<CachedUniform> flywheel$completeOrder;
    @Unique
    private long flywheel$uniformEpoch;
    @Unique
    private final Set<CachedUniform> flywheel$unevaluated = new ReferenceOpenHashSet<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$rememberUniforms(CallbackInfo _) {
        flywheel$completeOrder = List.copyOf(uniformOrder);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void flywheel$advanceUniformEpoch(CallbackInfo _) {
        flywheel$unevaluated.clear();
        flywheel$uniformEpoch++;
    }

    @Override
    public void flywheel$registerGuestUniforms(LocationalUniformHolder holder) {
        // Native passes are optimised before guest programs exist. Rebuild their dependency closure for this holder.
        var retained = new ReferenceOpenHashSet<>(uniformOrder);
        uniformOrder.clear();
        uniformOrder.addAll(flywheel$completeOrder);
        assignTo(holder);
        optimise();
        for (CachedUniform uniform : uniformOrder) {
            if (!retained.contains(uniform)) {
                flywheel$unevaluated.add(uniform);
            }
        }
    }

    @Override
    public void flywheel$evaluateNewlyRetained() {
        if (flywheel$unevaluated.isEmpty()) {
            return;
        }
        for (CachedUniform uniform : uniformOrder) {
            if (flywheel$unevaluated.contains(uniform)) {
                uniform.update();
            }
        }
        flywheel$unevaluated.clear();
    }

    @Override
    public long flywheel$uniformEpoch() {
        return flywheel$uniformEpoch;
    }
}
