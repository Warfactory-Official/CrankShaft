package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.BillboardInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

public class ExperienceOrbVisual extends AbstractEntityVisual<EntityXPOrb> implements SimpleDynamicVisual {
    // CUTOUT, not TRANSLUCENT: pass-0 entities draw with blend disabled in 1.12.2, so vanilla orbs
    // render opaque at full intensity — the 128 vertex alpha only feeds the 0.1 alpha test.
    private static final Material MATERIAL = SimpleMaterial.builderOf(Materials.CUTOUT)
            .texture(new ResourceLocation("textures/entity/experience_orb.png"))
            .cutout(CutoutShaders.ONE_TENTH)
            // Vanilla's standard item lighting clamps to 1.0 for the orb's constant (0,1,0) normal.
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useOverlay(false)
            .mipmap(false)
            .build();
    private static final Model MODEL = new SingleMeshModel(BillboardQuadMesh.INSTANCE, MATERIAL);

    private final BillboardInstance instance;
    private final ShadowComponent shadow;
    private int frame = -1;

    public ExperienceOrbVisual(VisualizationContext ctx, EntityXPOrb entity, float partialTick) {
        super(ctx, entity, partialTick);
        instance = instancerProvider().instancer(InstanceTypes.BILLBOARD, MODEL)
                .createInstance();
        instance.size(0.3F);
        updateInstance(partialTick);
        // RenderXPOrb ctor: shadowSize 0.15, shadowOpaque 0.75.
        shadow = new ShadowComponent(ctx, entity).radius(0.15F).strength(0.75F);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        updateInstance(ctx.partialTick());
        shadow.beginFrame(ctx);
    }

    private void updateInstance(float partialTick) {
        var p = getVisualPosition(partialTick);
        // Vanilla anchors the quad 0.1 above the entity position.
        instance.position(p.x, p.y + 0.1F, p.z);

        int tex = entity.getTextureByXP();
        if (tex != frame) {
            frame = tex;
            instance.uvRegion(tex % 4 * 0.25F, tex / 4 * 0.25F, 0.25F, 0.25F);
        }

        float angle = (entity.xpColor + partialTick) / 2.0F;
        int red = (int) ((MathHelper.sin(angle) + 1.0F) * 0.5F * 255.0F);
        int blue = (int) ((MathHelper.sin(angle + ((float) Math.PI * 4F / 3F)) + 1.0F) * 0.1F * 255.0F);
        instance.color(red, 255, blue, 128);

        // Mirrors EntityXPOrb.getBrightnessForRender: +0.5 block light (120/240), capped at full.
        int light = computePackedLight(partialTick);
        instance.light((light & 0xFFFF0000) | Math.min((light & 0xFFFF) + 120, 240));
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
        shadow.delete();
    }
}
