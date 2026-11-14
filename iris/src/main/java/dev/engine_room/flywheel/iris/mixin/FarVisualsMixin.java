package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.impl.far.FarSurface;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.iris.compile.GuestSsbos;
import dev.engine_room.flywheel.iris.compile.FarMaterialTextures;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarVisuals.class)
abstract class FarVisualsMixin {
    @Shadow
    public abstract @Nullable FarSurface surface();

    @Shadow
    public abstract boolean prepared();

    @Shadow
    public abstract @Nullable FarStaticMeshes staticMeshes();

    // Far compute and bake draws reuse SSBO slots that Iris installs only once per pipeline.
    @Inject(method = "render", at = @At("RETURN"))
    private void flywheel$restorePackStorage(RenderContext frame, Vec3i origin, CallbackInfo ci) {
        var meshes = staticMeshes();
        boolean frozen = meshes != null && meshes.size() != 0;
        if ((prepared() || frozen)
                && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            FarMaterialTextures.refresh(pipeline);
            GuestSsbos.restore(pipeline);
        }
    }
}
