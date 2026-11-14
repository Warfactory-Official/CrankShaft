package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class DhLevelRendererMixin {
    // Compat with DH 3.3.4 Fabric: its HEAD updates the view but leaves packs to an open terrain-pass hook.
    @Inject(method = "prepareTranslucents", at = @At("TAIL"))
    private void flywheel$renderOpaqueLods(CallbackInfo _) {
        if (ShadersModHelper.isShaderPackInUse() && !GuestTerrainGate.renderingShadow()) {
            ClientApi.INSTANCE.renderLods();
        }
    }
}
