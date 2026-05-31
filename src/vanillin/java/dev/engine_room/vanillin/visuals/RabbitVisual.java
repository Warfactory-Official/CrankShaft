package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.RabbitEntityModel;
import net.minecraft.client.model.ModelRabbit;
import net.minecraft.entity.passive.EntityRabbit;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import org.joml.Matrix4f;

/** Rabbit — 7 skins (6 types + the named "Toast") in one atlas, selected per instance like
 *  {@code RenderRabbit.getEntityTexture}. The killer bunny (type 99) falls back to vanilla. The adult
 *  model's 0.6 scale + (0,1,0) lift (in {@code ModelRabbit.render}) is reproduced in {@code preRenderCallback}. */
public final class RabbitVisual extends AbstractLivingEntityVisual<EntityRabbit, ModelRabbit> {
    private static final ResourceLocation BROWN = new ResourceLocation("textures/entity/rabbit/brown.png");
    private static final ResourceLocation WHITE = new ResourceLocation("textures/entity/rabbit/white.png");
    private static final ResourceLocation BLACK = new ResourceLocation("textures/entity/rabbit/black.png");
    private static final ResourceLocation WHITE_SPLOTCHED = new ResourceLocation("textures/entity/rabbit/white_splotched.png");
    private static final ResourceLocation GOLD = new ResourceLocation("textures/entity/rabbit/gold.png");
    private static final ResourceLocation SALT = new ResourceLocation("textures/entity/rabbit/salt.png");
    private static final ResourceLocation TOAST = new ResourceLocation("textures/entity/rabbit/toast.png");
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/rabbit"),
            BROWN, WHITE, BLACK, WHITE_SPLOTCHED, GOLD, SALT, TOAST);

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityRabbit entity) {
        return !entity.isInvisible() && entity.getRabbitType() != 99 && ATLAS.ready();
    }

    // Cell add order: brown, white, black, white_splotched, gold, salt (= getRabbitType 0..5), toast.
    private static int skinIndex(EntityRabbit entity) {
        // Gate on hasCustomName before the name path: getName() for a no-name rabbit does a registry +
        // translation lookup and the format-code strip allocates a Matcher, all per frame otherwise.
        if (entity.hasCustomName() && "Toast".equals(TextFormatting.getTextWithoutFormattingCodes(entity.getCustomNameTag()))) {
            return 6;
        }
        int type = entity.getRabbitType();
        return type >= 1 && type <= 5 ? type : 0;
    }

    public RabbitVisual(VisualizationContext ctx, EntityRabbit entity, float partialTick) {
        super(ctx, entity, partialTick, new RabbitEntityModel(), ATLAS.material(), "rabbit", 0.3F);
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityRabbit entity) {
        return ATLAS.cell(skinIndex(entity));
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    // ModelRabbit.render (adult branch) does scale(0.6) then translate(0, 16*0.0625, 0) = (0,1,0), but as a
    // model-internal transform vanilla runs it AFTER prepareScale's translate(0,-1.501,0) — i.e. the chain is
    // T(0,-1.501,0)·S(0.6)·T(0,1,0). buildRoot appends T(0,-1.501,0) after this hook, so reproduce the desired
    // chain here and cancel the trailing translate: T(0,-1.501,0)·S(0.6)·T(0,2.501,0)·T(0,-1.501,0) collapses
    // to the vanilla form. Emitting scale(0.6)·translate(0,1,0) here (before the trailing translate) would
    // float adult rabbits ~0.6 blocks too high. The baby branch replaces this with babyTransform's groups.
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        if (entity.isChild()) {
            return;
        }
        dest.translate(0.0F, -1.501F, 0.0F);
        dest.scale(0.6F);
        dest.translate(0.0F, 2.501F, 0.0F);
    }
}
