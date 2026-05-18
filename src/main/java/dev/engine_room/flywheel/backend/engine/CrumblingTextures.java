package dev.engine_room.flywheel.backend.engine;

import net.minecraft.util.ResourceLocation;

/**
 * CrankShaft shim: 1.12.2 has no {@code net.minecraft.client.resources.model.ModelBakery.BREAKING_LOCATIONS}
 * constant. Vanilla 1.12.2 references the destroy stage textures inline as
 * {@code minecraft:textures/blocks/destroy_stage_{0..9}.png}. This array mirrors that and exposes
 * the same indexed lookup the upstream {@code renderCrumbling} path expects.
 */
public final class CrumblingTextures {
    public static final int STAGES = 10;

    public static final ResourceLocation[] BREAKING_LOCATIONS = makeBreakingLocations();

    private CrumblingTextures() {
    }

    private static ResourceLocation[] makeBreakingLocations() {
        ResourceLocation[] out = new ResourceLocation[STAGES];
        for (int i = 0; i < STAGES; i++) {
            out[i] = new ResourceLocation("minecraft", "textures/blocks/destroy_stage_" + i + ".png");
        }
        return out;
    }
}
