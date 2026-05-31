package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.ShulkerEntityModel;
import net.minecraft.client.model.ModelShulker;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

// Unlike vanilla RenderShulker.doRender, the teleport-interp easing (sub-frame slide on teleport) is not reproduced.
public final class ShulkerVisual extends AbstractLivingEntityVisual<EntityShulker, ModelShulker> {
    // Indexed by EnumDyeColor.getMetadata() (0..15), matching RenderShulker.SHULKER_ENDERGOLEM_TEXTURE.
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/shulker/shulker_white.png"),
            new ResourceLocation("textures/entity/shulker/shulker_orange.png"),
            new ResourceLocation("textures/entity/shulker/shulker_magenta.png"),
            new ResourceLocation("textures/entity/shulker/shulker_light_blue.png"),
            new ResourceLocation("textures/entity/shulker/shulker_yellow.png"),
            new ResourceLocation("textures/entity/shulker/shulker_lime.png"),
            new ResourceLocation("textures/entity/shulker/shulker_pink.png"),
            new ResourceLocation("textures/entity/shulker/shulker_gray.png"),
            new ResourceLocation("textures/entity/shulker/shulker_silver.png"),
            new ResourceLocation("textures/entity/shulker/shulker_cyan.png"),
            new ResourceLocation("textures/entity/shulker/shulker_purple.png"),
            new ResourceLocation("textures/entity/shulker/shulker_blue.png"),
            new ResourceLocation("textures/entity/shulker/shulker_brown.png"),
            new ResourceLocation("textures/entity/shulker/shulker_green.png"),
            new ResourceLocation("textures/entity/shulker/shulker_red.png"),
            new ResourceLocation("textures/entity/shulker/shulker_black.png"),
    };
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/shulker"), SKINS);

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityShulker entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    static int skinIndex(EntityShulker entity) {
        int m = entity.getColor().getMetadata();
        return m < 0 || m >= SKINS.length ? 0 : m;
    }

    public ShulkerVisual(VisualizationContext ctx, EntityShulker entity, float partialTick) {
        super(ctx, entity, partialTick, new ShulkerEntityModel(), ATLAS.material(), "shulker", 0.0F);
        addLayer(new ShulkerHeadLayer(ctx, entity, model, ATLAS, 1));
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityShulker entity) {
        return ATLAS.cell(skinIndex(entity));
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    // RenderShulker.applyRotations: body-yaw/death flop, then orient to the attachment face (pre-flip frame).
    @Override
    protected void applyRotations(Matrix4f dest, float bodyYaw, float partialTick) {
        super.applyRotations(dest, bodyYaw, partialTick);
        switch (entity.getAttachmentFacing()) {
            case EAST:
                dest.translate(0.5F, 0.5F, 0.0F);
                dest.rotateX((float) Math.toRadians(90.0));
                dest.rotateZ((float) Math.toRadians(90.0));
                break;
            case WEST:
                dest.translate(-0.5F, 0.5F, 0.0F);
                dest.rotateX((float) Math.toRadians(90.0));
                dest.rotateZ((float) Math.toRadians(-90.0));
                break;
            case NORTH:
                dest.translate(0.0F, 0.5F, -0.5F);
                dest.rotateX((float) Math.toRadians(90.0));
                break;
            case SOUTH:
                dest.translate(0.0F, 0.5F, 0.5F);
                dest.rotateX((float) Math.toRadians(90.0));
                dest.rotateZ((float) Math.toRadians(180.0));
                break;
            case UP:
                dest.translate(0.0F, 1.0F, 0.0F);
                dest.rotateX((float) Math.toRadians(180.0));
                break;
            case DOWN:
            default:
                break;
        }
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.999F);
    }
}
