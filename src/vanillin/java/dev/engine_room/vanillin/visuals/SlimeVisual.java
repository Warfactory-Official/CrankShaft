package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.SlimeEntityModel;
import dev.engine_room.flywheel.lib.visual.SlimeGelModel;
import net.minecraft.client.model.ModelSlime;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

public final class SlimeVisual extends AbstractLivingEntityVisual<EntitySlime, ModelSlime> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/slime/slime.png");
    private static final Material BODY = EntityMaterials.living("textures/entity/slime/slime.png");
    private static final Material GEL = SimpleMaterial.builderOf(Materials.TRANSLUCENT_ENTITY)
            .cardinalLightingMode(CardinalLightingMode.ENTITY)
            .transparency(Transparency.ORDER_INDEPENDENT)
            .backfaceCulling(false)
            .texture(TEXTURE)
            .build();

    public SlimeVisual(VisualizationContext ctx, EntitySlime entity, float partialTick) {
        // Shadow scales with size; size is fixed for the entity's lifetime.
        super(ctx, entity, partialTick, new SlimeEntityModel(), BODY, "vanillin:slime", 0.25F * entity.getSlimeSize());
        addLayer(new SlimeGelLayer(ctx, entity, new SlimeGelModel(), GEL, "vanillin:slime_gel", 1));
    }

    // RenderSlime.preRenderCallback: leading 0.999 shrink, then size + squish (x/z by f3, y by 1/f3).
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.999F);
        float size = entity.getSlimeSize();
        float f2 = (entity.prevSquishFactor + (entity.squishFactor - entity.prevSquishFactor) * partialTick) / (size * 0.5F + 1.0F);
        float f3 = 1.0F / (f2 + 1.0F);
        dest.scale(f3 * size, (1.0F / f3) * size, f3 * size);
    }
}
