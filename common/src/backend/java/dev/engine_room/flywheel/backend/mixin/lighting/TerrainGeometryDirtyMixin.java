package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.LightingCompatibility;
import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class TerrainGeometryDirtyMixin {
    @Shadow
    private @Nullable ClientLevel level;

    // Off-screen native sections still contribute to AO; normal visibility scheduling need not compile them.
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("RETURN"))
    private void lighting$geometryDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        if (!LightingCompatibility.SODIUM) TerrainGeometryCache.request(level, SectionPos.asLong(x, y, z));
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void lighting$geometryReload(CallbackInfo ci) {
        TerrainGeometryCache.invalidate(level);
    }
}
