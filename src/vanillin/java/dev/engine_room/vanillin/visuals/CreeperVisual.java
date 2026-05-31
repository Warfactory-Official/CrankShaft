package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.CreeperEntityModel;
import net.minecraft.client.model.ModelCreeper;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

public final class CreeperVisual extends AbstractLivingEntityVisual<EntityCreeper, ModelCreeper> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/creeper/creeper.png");
    // Charged aura (mirrors WitherVisual): depth-write prunes the inflated shell's overlapping faces.
    private static final Material AURA = SimpleMaterial.builderOf(Materials.ADDITIVE_NO_CULL)
            .texture(new ResourceLocation("textures/entity/creeper/creeper_armor.png"))
            .mipmap(false)
            .fog(FogShaders.LINEAR_FADE)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build();

    public CreeperVisual(VisualizationContext ctx, EntityCreeper entity, float partialTick) {
        super(ctx, entity, partialTick, new CreeperEntityModel(), MATERIAL, "vanillin:creeper", 0.5F);
        addLayer(new CreeperChargeLayer(ctx, entity, instances, AURA, "vanillin:creeper:charge", 1));
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        float f = entity.getCreeperFlashIntensity(partialTick);
        float f1 = 1.0F + MathHelper.sin(f * 100.0F) * f * 0.01F;
        f = MathHelper.clamp(f, 0.0F, 1.0F);
        f = f * f;
        f = f * f;
        float f2 = (1.0F + f * 0.4F) * f1;
        float f3 = (1.0F + f * 0.1F) / f1;
        dest.scale(f2, f3, f2);
    }

    @Override
    protected int overlayCoord(EntityCreeper entity, float partialTick) {
        if (entity.hurtTime > 0 || entity.deathTime > 0) {
            return OverlayTexture.HURT;
        }
        float f = entity.getCreeperFlashIntensity(partialTick);
        if ((int) (f * 10.0F) % 2 == 0) {
            return OverlayTexture.NO_OVERLAY;
        }
        return OverlayTexture.whitePack(MathHelper.clamp(f * 0.2F, 0.0F, 1.0F));
    }
}
