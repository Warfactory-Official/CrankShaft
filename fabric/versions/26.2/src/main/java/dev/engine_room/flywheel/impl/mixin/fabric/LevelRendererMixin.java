package dev.engine_room.flywheel.impl.mixin.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedSet;

@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class LevelRendererMixin {
    @Inject(method = "*",
            at = @At(value = "INVOKE",
                    target =
                    "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void flywheel$afterSolidFeatures(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            return;
        }
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        if (manager == null) {
            return;
        }
        RenderContextImpl ctx = RenderContextImpl.captureCurrent((LevelRenderer) (Object) this, level,
                RenderSystem.getModelViewStack());
        manager.renderDispatcher()
               .afterEntities(ctx);
        manager.renderDispatcher()
               .beforeCrumbling(ctx,
                       (Long2ObjectOpenHashMap<SortedSet<BlockDestructionProgress>>) level.destructionProgress());
    }
}
