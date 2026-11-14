package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.lib.util.TerrainOverlays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererTerrainOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void flywheel$beginTerrainOverlays(CallbackInfo _) {
        var client = Minecraft.getInstance();
        if (TerrainOverlays.get(client.level).isEmpty()) {
            TerrainOverlayDraw.beginEmptyFrame();
            return;
        }
        TerrainOverlayDraw.beginFrame(RenderContextImpl.captureCurrent(
                (LevelRenderer) (Object) this, client.level, RenderSystem.getModelViewStack()));
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$closeTerrainOverlays(CallbackInfo _) {
        TerrainOverlayDraw.close();
    }
}
