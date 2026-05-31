package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.WitherEntityModel;
import net.minecraft.client.model.ModelWither;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

/** Wither — 3 heads + spine at 2× scale, plus the additive charging aura ({@link WitherAuraLayer}). The 220-tick
 *  invulnerable spawn (which flickers between two textures) is left to vanilla via {@link #isInstanceable}; once
 *  spawned, the instanced path takes over. Head swing + spine animation ride {@code setLivingAnimations}. */
public final class WitherVisual extends AbstractLivingEntityVisual<EntityWither, ModelWither> {
    private static final Material BODY = EntityMaterials.living("textures/entity/wither/wither.png");
    private static final Material AURA = SimpleMaterial.builderOf(Materials.ADDITIVE_NO_CULL)
            .texture(new ResourceLocation("textures/entity/wither/wither_armor.png"))
            .mipmap(false)
            // LayerWitherAura forces black fog (additive fades to zero with distance) and writes depth for the
            // non-invisible wither (instanced path is !invisible). LINEAR_FADE reproduces the fade; COLOR_DEPTH
            // restores the depth write so the inflated shell's overlapping faces depth-prune instead of stacking.
            .fog(FogShaders.LINEAR_FADE)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build();

    public static boolean isInstanceable(EntityWither entity) {
        return !entity.isInvisible() && entity.getInvulTime() <= 0;
    }

    public WitherVisual(VisualizationContext ctx, EntityWither entity, float partialTick) {
        super(ctx, entity, partialTick, new WitherEntityModel(0.0F), BODY, "wither", 1.0F);
        addLayer(new WitherAuraLayer(ctx, entity, instances, new WitherEntityModel(0.5F), AURA, "wither:aura", 1));
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    // RenderWither.preRenderCallback: 2× scale, shrinking slightly during the (deferred) invul spawn ramp.
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        float f = 2.0F;
        int i = entity.getInvulTime();
        if (i > 0) {
            f -= (i - partialTick) / 220.0F * 0.5F;
        }
        dest.scale(f);
    }
}
