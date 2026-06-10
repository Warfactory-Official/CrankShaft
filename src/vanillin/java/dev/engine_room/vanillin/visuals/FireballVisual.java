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
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.projectile.EntityDragonFireball;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.init.Items;
import net.minecraft.util.ResourceLocation;

/** Mirrors {@code RenderFireball}/{@code RenderDragonFireball}: a billboarded sprite quad, fullbright.
 *  Small/large fireballs use the fire charge particle sprite (scale 0.5/2.0); the dragon fireball uses
 *  its dedicated texture at scale 2.0. */
public class FireballVisual extends AbstractEntityVisual<EntityFireball> implements SimpleDynamicVisual {
    private static final Material FIRE_CHARGE_MATERIAL = SimpleMaterial.builderOf(EntityMaterials.BLOCK_OVERLAY)
            .cutout(CutoutShaders.ONE_TENTH)
            .build();
    private static final Model FIRE_CHARGE_MODEL = new SingleMeshModel(BillboardQuadMesh.INSTANCE, FIRE_CHARGE_MATERIAL);

    private static final Material DRAGON_FIREBALL_MATERIAL = SimpleMaterial.builderOf(Materials.CUTOUT)
            .texture(new ResourceLocation("textures/entity/enderdragon/dragon_fireball.png"))
            .cutout(CutoutShaders.ONE_TENTH)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useOverlay(false)
            .mipmap(false)
            .build();
    private static final Model DRAGON_FIREBALL_MODEL = new SingleMeshModel(BillboardQuadMesh.INSTANCE, DRAGON_FIREBALL_MATERIAL);

    private final BillboardInstance instance;

    public FireballVisual(VisualizationContext ctx, EntityFireball entity, float partialTick, float size) {
        this(ctx, entity, partialTick, FIRE_CHARGE_MODEL, size, true);
    }

    private FireballVisual(VisualizationContext ctx, EntityFireball entity, float partialTick,
                           Model model, float size, boolean fireChargeUv) {
        super(ctx, entity, partialTick);
        instance = instancerProvider().instancer(InstanceTypes.BILLBOARD, model)
                .createInstance();
        instance.size(size);
        // EntityFireball.getBrightnessForRender is constant fullbright.
        instance.light(LightTexture.FULL_BRIGHT);
        if (fireChargeUv) {
            // Visuals are rebuilt on resource reload, so capturing the sprite's UV rect here is safe.
            TextureAtlasSprite sprite = Minecraft.getMinecraft().getRenderItem().getItemModelMesher()
                    .getParticleIcon(Items.FIRE_CHARGE);
            instance.uvRegion(sprite.getMinU(), sprite.getMinV(),
                    sprite.getMaxU() - sprite.getMinU(), sprite.getMaxV() - sprite.getMinV());
        }
        updateInstance(partialTick);
    }

    public static FireballVisual dragon(VisualizationContext ctx, EntityDragonFireball entity, float partialTick) {
        return new FireballVisual(ctx, entity, partialTick, DRAGON_FIREBALL_MODEL, 2.0F, false);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        updateInstance(ctx.partialTick());
    }

    private void updateInstance(float partialTick) {
        var p = getVisualPosition(partialTick);
        instance.position(p.x, p.y, p.z);
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }
}
