package dev.engine_room.flywheel.backend.mixin.mod.cdl_sodium_fork;

import me.jellysquid.mods.sodium.client.model.light.EntityLighter;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;
import toni.sodiumdynamiclights.config.DynamicLightsConfig;

// Sodium forks (Neonium/Vintagium/Relictium) @Redirect entity.getBrightnessForRender() inside
// RenderManager.renderEntityStatic to route through EntityLighter.getBlendedLight when
// smooth-lighting is set to MAXIMUM. EntityLighter samples raw block/sky light grids directly
// and never calls getBrightnessForRender or WorldClient.getCombinedLight, so CDL's
// onGetBrightnessForRender hook is bypassed entirely. Re-apply CDL's standard
// max(posLuminance, entityLuminance) bump at RETURN so dynamic lights survive smooth-lighting
// MAXIMUM with CDL + a sodium fork.
@Mixin(value = EntityLighter.class, remap = false)
public abstract class MixinSodiumEntityLighterCdl {

    @Dynamic
    @Inject(method = "getBlendedLight", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void flw$applyCdlBump(Entity entity, float tickDelta, CallbackInfoReturnable<Integer> cir) {
        if (!DynamicLightsConfig.dynamicLightsMode.isEnabled()) return;
        int blended = cir.getReturnValue();
        SodiumDynamicLights sdl = SodiumDynamicLights.get();
        double posLight = sdl.getDynamicLightLevel(entity.getPosition());
        int entityLuminance = ((DynamicLightSource) entity).sdl$getLuminance();
        int bumped = sdl.getLightmapWithDynamicLight(Math.max(posLight, entityLuminance), blended);
        if (bumped != blended) {
            cir.setReturnValue(bumped);
        }
    }
}
