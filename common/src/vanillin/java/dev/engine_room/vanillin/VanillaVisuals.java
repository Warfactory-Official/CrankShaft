package dev.engine_room.vanillin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.SimpleMaterialShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.vanillin.compose.*;
import dev.engine_room.vanillin.config.BlockEntityVisualizerBuilder;
import dev.engine_room.vanillin.config.Configurator;
import dev.engine_room.vanillin.config.EntityVisualizerBuilder;
import dev.engine_room.vanillin.elements.ShadowElement;
import dev.engine_room.vanillin.visuals.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.animal.armadillo.AdultArmadilloModel;
import net.minecraft.client.model.animal.armadillo.BabyArmadilloModel;
import net.minecraft.client.model.animal.axolotl.AdultAxolotlModel;
import net.minecraft.client.model.animal.axolotl.BabyAxolotlModel;
import net.minecraft.client.model.animal.bee.AdultBeeModel;
import net.minecraft.client.model.animal.bee.BabyBeeModel;
import net.minecraft.client.model.animal.camel.AdultCamelModel;
import net.minecraft.client.model.animal.camel.BabyCamelModel;
import net.minecraft.client.model.animal.chicken.AdultChickenModel;
import net.minecraft.client.model.animal.chicken.BabyChickenModel;
import net.minecraft.client.model.animal.chicken.ColdChickenModel;
import net.minecraft.client.model.animal.cow.BabyCowModel;
import net.minecraft.client.model.animal.cow.CowModel;
import net.minecraft.client.model.animal.dolphin.DolphinModel;
import net.minecraft.client.model.animal.equine.BabyDonkeyModel;
import net.minecraft.client.model.animal.equine.BabyHorseModel;
import net.minecraft.client.model.animal.equine.DonkeyModel;
import net.minecraft.client.model.animal.equine.HorseModel;
import net.minecraft.client.model.animal.feline.AdultCatModel;
import net.minecraft.client.model.animal.feline.AdultOcelotModel;
import net.minecraft.client.model.animal.feline.BabyCatModel;
import net.minecraft.client.model.animal.feline.BabyOcelotModel;
import net.minecraft.client.model.animal.fish.*;
import net.minecraft.client.model.animal.fox.AdultFoxModel;
import net.minecraft.client.model.animal.fox.BabyFoxModel;
import net.minecraft.client.model.animal.ghast.HappyGhastModel;
import net.minecraft.client.model.animal.goat.BabyGoatModel;
import net.minecraft.client.model.animal.goat.GoatModel;
import net.minecraft.client.model.animal.llama.BabyLlamaModel;
import net.minecraft.client.model.animal.llama.LlamaModel;
import net.minecraft.client.model.animal.nautilus.NautilusModel;
import net.minecraft.client.model.animal.panda.BabyPandaModel;
import net.minecraft.client.model.animal.panda.PandaModel;
import net.minecraft.client.model.animal.pig.BabyPigModel;
import net.minecraft.client.model.animal.pig.ColdPigModel;
import net.minecraft.client.model.animal.pig.PigModel;
import net.minecraft.client.model.animal.polarbear.PolarBearModel;
import net.minecraft.client.model.animal.rabbit.AdultRabbitModel;
import net.minecraft.client.model.animal.rabbit.BabyRabbitModel;
import net.minecraft.client.model.animal.sheep.BabySheepModel;
import net.minecraft.client.model.animal.sheep.SheepModel;
import net.minecraft.client.model.animal.sniffer.SnifferModel;
import net.minecraft.client.model.animal.squid.SquidModel;
import net.minecraft.client.model.animal.turtle.AdultTurtleModel;
import net.minecraft.client.model.animal.turtle.BabyTurtleModel;
import net.minecraft.client.model.animal.wolf.AdultWolfModel;
import net.minecraft.client.model.animal.wolf.BabyWolfModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.monster.hoglin.BabyHoglinModel;
import net.minecraft.client.model.monster.hoglin.HoglinModel;
import net.minecraft.client.model.monster.nautilus.ZombieNautilusCoralModel;
import net.minecraft.client.model.monster.piglin.AdultPiglinModel;
import net.minecraft.client.model.monster.piglin.AdultZombifiedPiglinModel;
import net.minecraft.client.model.monster.piglin.BabyPiglinModel;
import net.minecraft.client.model.monster.piglin.BabyZombifiedPiglinModel;
import net.minecraft.client.model.monster.slime.SulfurCubeModel;
import net.minecraft.client.model.monster.strider.AdultStriderModel;
import net.minecraft.client.model.monster.strider.BabyStriderModel;
import net.minecraft.client.model.monster.zombie.*;
import net.minecraft.client.model.npc.BabyVillagerModel;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.model.object.armorstand.ArmorStandModel;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.resources.metadata.animation.VillagerMetadataSection;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.chicken.ChickenVariant;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.CowVariant;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemOxidationLevels;
import net.minecraft.world.entity.animal.nautilus.ZombieNautilus;
import net.minecraft.world.entity.animal.nautilus.ZombieNautilusVariant;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.pig.PigVariant;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public class VanillaVisuals {
    public static final Configurator CONFIGURATOR = new Configurator();

    // Stable visuals are enabled by default always.
    public static final boolean STABLE = true;
    // Experimental visuals are enabled by default in dev.
    public static final boolean EXPERIMENTAL = VanillinXplat.INSTANCE.isDevelopmentEnvironment();
    private static final Identifier STRAY_CLOTHES = Identifier.withDefaultNamespace(
            "textures/entity/skeleton/stray_overlay.png");
    private static final Identifier SLIME_TEXTURE = Identifier.withDefaultNamespace("textures/entity/slime/slime.png");
    private static final Identifier BREEZE_WIND_TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/breeze/breeze_wind.png");
    private static final Identifier SPIDER_EYES = Identifier.withDefaultNamespace(
            "textures/entity/spider/spider_eyes.png");
    private static final Identifier ENDERMAN_EYES = Identifier.withDefaultNamespace(
            "textures/entity/enderman/enderman_eyes.png");
    private static final Identifier BREEZE_EYES = Identifier.withDefaultNamespace(
            "textures/entity/breeze/breeze_eyes.png");
    private static final Matrix4fc PUMPKIN = new Matrix4f()
            .translate(0.0F, -0.34375F, 0.0F)
            .rotateY((float) Math.toRadians(180.0))
            .scale(0.625F, -0.625F, -0.625F)
            .translate(-0.5F, -0.5F, -0.5F);
    private static final Matrix4fc MUSHROOM_1 = new Matrix4f()
            .translate(0.2F, -0.35F, 0.5F)
            .rotateY((float) Math.toRadians(-48.0))
            .scale(-1.0F, -1.0F, 1.0F)
            .translate(-0.5F, -0.5F, -0.5F);
    private static final Matrix4fc MUSHROOM_2 = new Matrix4f()
            .translate(0.2F, -0.35F, 0.5F)
            .rotateY((float) Math.toRadians(42.0))
            .translate(0.1F, 0.0F, -0.6F)
            .rotateY((float) Math.toRadians(-48.0))
            .scale(-1.0F, -1.0F, 1.0F)
            .translate(-0.5F, -0.5F, -0.5F);
    private static final Matrix4fc MUSHROOM_3 = new Matrix4f()
            .translate(0.0F, -0.7F, -0.2F)
            .rotateY((float) Math.toRadians(-78.0))
            .scale(-1.0F, -1.0F, 1.0F)
            .translate(-0.5F, -0.5F, -0.5F);
    private static final Function<LivingEntity, BlockState> MOOSHROOM_MUSHROOM = e ->
            (((MushroomCow) e).getVariant() == MushroomCow.Variant.BROWN ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM).defaultBlockState();
    private static final Function<LivingEntity, BlockState> SNOW_GOLEM_PUMPKIN = e -> Blocks.CARVED_PUMPKIN.defaultBlockState();
    private static final Function<LivingEntity, BlockState> IRON_GOLEM_FLOWER = e -> Blocks.POPPY.defaultBlockState();
    private static final Matrix4fc IRON_FLOWER = new Matrix4f()
            .translate(-1.1875F, 1.0625F, -0.9375F)
            .translate(0.5F, 0.5F, 0.5F)
            .scale(0.5F)
            .rotateX((float) Math.toRadians(-90.0))
            .translate(-0.5F, -0.5F, -0.5F);
    private static final Identifier IRON_CRACK_LOW = Identifier.withDefaultNamespace(
            "textures/entity/iron_golem/iron_golem_crackiness_low.png");
    private static final Identifier IRON_CRACK_MEDIUM = Identifier.withDefaultNamespace(
            "textures/entity/iron_golem/iron_golem_crackiness_medium.png");
    private static final Identifier IRON_CRACK_HIGH = Identifier.withDefaultNamespace(
            "textures/entity/iron_golem/iron_golem_crackiness_high.png");
    private static final Predicate<LivingEntityRenderState> SNOW_GOLEM_HAS_PUMPKIN = s -> !((SnowGolemRenderState) s).headBlock.isEmpty();
    private static final Predicate<LivingEntityRenderState> IRON_GOLEM_OFFERING = s -> !((IronGolemRenderState) s).flowerBlock.isEmpty();
    private static final Matrix4fc ENDERMAN_BLOCK = new Matrix4f()
            .translate(0.0F, 0.6875F, -0.75F)
            .rotateX((float) Math.toRadians(20.0))
            .rotateY((float) Math.toRadians(45.0))
            .translate(0.25F, 0.1875F, 0.25F)
            .scale(-0.5F, -0.5F, 0.5F)
            .rotateY((float) Math.toRadians(90.0));
    private static final Function<LivingEntity, BlockState> ENDERMAN_CARRIED = e -> ((EnderMan) e).getCarriedBlock();
    private static final LivingEntityVisual.RootScale CUBE_SCALE = (state, pose) -> {
        SlimeRenderState s = (SlimeRenderState) state;
        float size = s.size;
        float squish = s.squish / (size * 0.5F + 1.0F);
        float w = 1.0F / (squish + 1.0F);
        pose.scale(w * size, size / w, w * size);
    };
    private static final LivingEntityVisual.RootScale CREEPER_SCALE = (state, pose) -> {
        float g = ((CreeperRenderState) state).swelling;
        float wobble = 1.0F + Mth.sin(g * 100.0F) * g * 0.01F;
        g = Mth.clamp(g, 0.0F, 1.0F);
        g *= g;
        g *= g;
        float s = (1.0F + g * 0.4F) * wobble;
        float hs = (1.0F + g * 0.1F) / wobble;
        pose.scale(s, hs, s);
    };
    private static final LivingEntityVisual.WhiteOverlay CREEPER_WHITE = state -> {
        float step = ((CreeperRenderState) state).swelling;
        return (int) (step * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(step, 0.5F, 1.0F);
    };
    private static final LivingEntityVisual.Rotations SQUID_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        SquidRenderState s = (SquidRenderState) state;
        pose.translate(0.0F, s.isBaby ? 0.25F : 0.5F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
        pose.mulPose(Axis.XP.rotationDegrees(s.xBodyRot));
        pose.mulPose(Axis.YP.rotationDegrees(s.zBodyRot));
        pose.translate(0.0F, s.isBaby ? -0.6F : -1.2F, 0.0F);
    };
    private static final LivingEntityVisual.Rotations COD_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        pose.mulPose(Axis.YP.rotationDegrees(4.3F * Mth.sin(0.6F * state.ageInTicks)));
        if (!state.isInWater) {
            pose.translate(0.1F, 0.1F, -0.1F);
            pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        }
    };
    private static final LivingEntityVisual.Rotations SALMON_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        float amplitude = state.isInWater ? 1.0F : 1.3F;
        float rate = state.isInWater ? 1.0F : 1.7F;
        pose.mulPose(Axis.YP.rotationDegrees(amplitude * 4.3F * Mth.sin(rate * 0.6F * state.ageInTicks)));
        if (!state.isInWater) {
            pose.translate(0.2F, 0.1F, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        }
    };
    private static final LivingEntityVisual.Config SALMON_SMALL_CFG = salmonCfg(ModelLayers.SALMON_SMALL);
    private static final LivingEntityVisual.Config SALMON_MEDIUM_CFG = salmonCfg(ModelLayers.SALMON);
    private static final LivingEntityVisual.Config SALMON_LARGE_CFG = salmonCfg(ModelLayers.SALMON_LARGE);
    private static final LivingEntityVisual.Rotations TROPICAL_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        pose.mulPose(Axis.YP.rotationDegrees(4.3F * Mth.sin(0.6F * state.ageInTicks)));
        if (!state.isInWater) {
            pose.translate(0.2F, 0.1F, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        }
    };
    private static final Map<TropicalFish.Pattern, Identifier> TROPICAL_PATTERNS = new EnumMap<>(
            TropicalFish.Pattern.class);
    private static final Function<LivingEntityRenderState, Identifier> TROPICAL_PATTERN =
            s -> TROPICAL_PATTERNS.computeIfAbsent(((TropicalFishRenderState) s).pattern,
                    VanillaVisuals::tropicalPattern);
    private static final LivingEntityVisual.Config TROPICAL_SMALL_CFG = tropicalCfg(true);
    private static final LivingEntityVisual.Config TROPICAL_LARGE_CFG = tropicalCfg(false);
    private static final LivingEntityVisual.Rotations PUFFERFISH_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        pose.translate(0.0F, Mth.cos(state.ageInTicks * 0.05F) * 0.08F, 0.0F);
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
    };
    private static final ToIntFunction<LivingEntityRenderState> PUFFER_STATE = s -> ((PufferfishRenderState) s).puffState;
    private static final LivingEntityVisual.ShadowRadius PUFFERFISH_SHADOW = e -> 0.1F + 0.1F * ((Pufferfish) e).getPuffState();
    private static final LivingEntityVisual.ShadowRadius VILLAGER_SHADOW =
            e -> 0.5F * e.getScale() * e.getAgeScale() * (e.isBaby() ? 0.5F : 1.0F);
    private static final LivingEntityVisual.ShadowRadius TURTLE_SHADOW =
            e -> 0.7F * e.getScale() * e.getAgeScale() * (e.isBaby() ? 0.83F : 1.0F);
    private static final LivingEntityVisual.ShadowRadius STRIDER_SHADOW =
            e -> 0.5F * e.getScale() * e.getAgeScale() * (e.isBaby() ? 0.5F : 1.0F);
    private static final LivingEntityVisual.Config ZOMBIE_NAUTILUS_CFG = zombieNautilusCfg(ModelLayers.ZOMBIE_NAUTILUS,
            NautilusModel::new);
    private static final LivingEntityVisual.Config ZOMBIE_NAUTILUS_CORAL_CFG = zombieNautilusCfg(
            ModelLayers.ZOMBIE_NAUTILUS_CORAL, ZombieNautilusCoralModel::new);
    private static final LivingEntityVisual.Rotations DROWNED_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        ZombieRenderState s = (ZombieRenderState) state;
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F,
                state.isFullyFrozen || s.isConverting);
        float swimAmount = s.swimAmount;
        if (swimAmount > 0.0F) {
            float targetRotationX = -10.0F - s.xRot;
            float rotationX = Mth.lerp(swimAmount, 0.0F, targetRotationX);
            pose.rotateAround(Axis.XP.rotationDegrees(rotationX), 0.0F, s.boundingBoxHeight / 2.0F / entityScale, 0.0F);
        }
    };
    private static final LivingEntityVisual.Rotations IRON_GOLEM_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        if (!(state.walkAnimationSpeed < 0.01)) {
            float wp = state.walkAnimationPos + 6.0F;
            float triangleWave = (Math.abs(wp % 13.0F - 6.5F) - 3.25F) / 3.25F;
            pose.mulPose(Axis.ZP.rotationDegrees(6.5F * triangleWave));
        }
    };
    private static final MaterialShaders BREEZE_WIND_SHADERS = new SimpleMaterialShaders(
            ResourceUtil.rl("material/breeze_wind.vert"), ResourceUtil.rl("material/default.frag"));
    private static final MaterialShaders ENERGY_SWIRL_SHADERS = new SimpleMaterialShaders(
            ResourceUtil.rl("material/energy_swirl.vert"), ResourceUtil.rl("material/default.frag"));
    private static final Identifier CREEPER_SWIRL_TEX = Identifier.withDefaultNamespace(
            "textures/entity/creeper/creeper_armor.png");
    private static final Material BREEZE_WIND_MATERIAL = SimpleMaterial.builder()
                                                                       .shaders(BREEZE_WIND_SHADERS)
                                                                       .backfaceCulling(false)
                                                                       .texture(BREEZE_WIND_TEXTURE)
                                                                       .transparency(Transparency.ORDER_INDEPENDENT)
                                                                       .build();
    private static final Material CREEPER_SWIRL_MATERIAL = SimpleMaterial.builder()
                                                                         .shaders(ENERGY_SWIRL_SHADERS)
                                                                         .backfaceCulling(false)
                                                                         .texture(CREEPER_SWIRL_TEX)
                                                                         .transparency(Transparency.ADDITIVE)
                                                                         .writeMask(WriteMask.COLOR)
                                                                         .cardinalLightingMode(CardinalLightingMode.OFF)
                                                                         .useLight(false)
                                                                         .build();
    private static final ToIntFunction<LivingEntityRenderState> CREEPER_SWIRL_TINT = s -> 0xFF808080;
    private static final Predicate<LivingEntityRenderState> CREEPER_POWERED = s -> ((CreeperRenderState) s).isPowered;
    private static final Identifier SHEEP_WOOL_TEX = Identifier.withDefaultNamespace(
            "textures/entity/sheep/sheep_wool.png");
    private static final Identifier SHEEP_BABY_WOOL_TEX = Identifier.withDefaultNamespace(
            "textures/entity/sheep/sheep_wool_baby.png");
    private static final Identifier SHEEP_UNDERCOAT_TEX = Identifier.withDefaultNamespace(
            "textures/entity/sheep/sheep_wool_undercoat.png");
    private static final Identifier WOLF_COLLAR_TEX = Identifier.withDefaultNamespace(
            "textures/entity/wolf/wolf_collar.png");
    private static final Identifier WOLF_BABY_COLLAR_TEX = Identifier.withDefaultNamespace(
            "textures/entity/wolf/wolf_collar_baby.png");
    private static final Identifier CAT_COLLAR_TEX = Identifier.withDefaultNamespace(
            "textures/entity/cat/cat_collar.png");
    private static final Identifier CAT_BABY_COLLAR_TEX = Identifier.withDefaultNamespace(
            "textures/entity/cat/cat_collar_baby.png");
    private static final Identifier DROWNED_OUTER_TEX = Identifier.withDefaultNamespace(
            "textures/entity/zombie/drowned_outer_layer.png");
    private static final Identifier BOGGED_OUTER_TEX = Identifier.withDefaultNamespace(
            "textures/entity/skeleton/bogged_overlay.png");
    private static final ToIntFunction<LivingEntityRenderState> SHEEP_WOOL_COLOR = s -> ((SheepRenderState) s).getWoolColor();
    private static final Predicate<LivingEntityRenderState> SHEEP_UNDERCOAT_VISIBLE = s -> {
        SheepRenderState ss = (SheepRenderState) s;
        return ss.isJebSheep || ss.woolColor != DyeColor.WHITE;
    };
    private static final ToIntFunction<LivingEntityRenderState> WOLF_COLLAR_COLOR = s -> {
        DyeColor c = ((WolfRenderState) s).collarColor;
        return c == null ? -1 : c.getTextureDiffuseColor();
    };
    private static final Predicate<LivingEntityRenderState> WOLF_HAS_COLLAR = s -> ((WolfRenderState) s).collarColor != null;
    private static final ToIntFunction<LivingEntityRenderState> CAT_COLLAR_COLOR = s -> {
        DyeColor c = ((CatRenderState) s).collarColor;
        return c == null ? -1 : c.getTextureDiffuseColor();
    };
    private static final Predicate<LivingEntityRenderState> CAT_HAS_COLLAR = s -> ((CatRenderState) s).collarColor != null;
    private static final LivingEntityVisual.Rotations CAT_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        CatRenderState s = (CatRenderState) state;
        float lie = s.lieDownAmount;
        if (lie > 0.0F) {
            pose.translate(0.4F * lie, 0.15F * lie, 0.1F * lie);
            pose.mulPose(Axis.ZP.rotationDegrees(Mth.rotLerp(lie, 0.0F, 90.0F)));
            if (s.isLyingOnTopOfSleepingPlayer) {
                pose.translate(0.15F * lie, 0.0F, 0.0F);
            }
        }
    };
    private static final Identifier WARDEN_BIOLUMINESCENT_TEX = Identifier.withDefaultNamespace(
            "textures/entity/warden/warden_bioluminescent_layer.png");
    private static final Identifier WARDEN_SPOTS_1_TEX = Identifier.withDefaultNamespace(
            "textures/entity/warden/warden_pulsating_spots_1.png");
    private static final Identifier WARDEN_SPOTS_2_TEX = Identifier.withDefaultNamespace(
            "textures/entity/warden/warden_pulsating_spots_2.png");
    private static final Identifier WARDEN_BASE_TEX = Identifier.withDefaultNamespace(
            "textures/entity/warden/warden.png");
    private static final Identifier WARDEN_HEART_TEX = Identifier.withDefaultNamespace(
            "textures/entity/warden/warden_heart.png");
    private static final Identifier PHANTOM_EYES = Identifier.withDefaultNamespace(
            "textures/entity/phantom/phantom_eyes.png");
    private static final LivingEntityVisual.RootScale WITHER_SCALE = (state, pose) -> {
        float scale = 2.0F;
        float inv = ((WitherRenderState) state).invulnerableTicks;
        if (inv > 0.0F) {
            scale -= inv / 220.0F * 0.5F;
        }
        pose.scale(scale, scale, scale);
    };
    private static final LivingEntityVisual.RootScale PHANTOM_SCALE = (state, pose) -> {
        float scale = 1.0F + 0.15F * ((PhantomRenderState) state).size;
        pose.scale(scale, scale, scale);
        pose.translate(0.0F, 1.3125F, 0.1875F);
    };
    private static final LivingEntityVisual.Rotations PHANTOM_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        pose.mulPose(Axis.XP.rotationDegrees(state.xRot));
    };
    private static final LivingEntityVisual.ItemPose WITCH_NOSE = (model, state) -> {
        PoseStack ps = new PoseStack();
        ModelPart root = model.root();
        root.translateAndRotate(ps);
        ModelPart head = root.getChild("head");
        head.translateAndRotate(ps);
        head.getChild("nose").translateAndRotate(ps);
        ps.translate(0.0625F, 0.25F, 0.0F);
        ps.mulPose(Axis.ZP.rotationDegrees(180.0F));
        ps.mulPose(Axis.XP.rotationDegrees(140.0F));
        ps.mulPose(Axis.ZP.rotationDegrees(10.0F));
        ps.mulPose(Axis.XP.rotationDegrees(180.0F));
        return new Matrix4f(ps.last().pose());
    };
    private static final LivingEntityVisual.Rotations FOX_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        FoxRenderState s = (FoxRenderState) state;
        if (s.isPouncing || s.isFaceplanted) {
            pose.mulPose(Axis.XP.rotationDegrees(-s.xRot));
        }
    };
    private static final LivingEntityVisual.ItemPose FOX_MOUTH = (model, state) -> {
        FoxRenderState s = (FoxRenderState) state;
        ModelPart head = model.root().getChild("head");
        PoseStack ps = new PoseStack();
        ps.translate(head.x / 16.0F, head.y / 16.0F, head.z / 16.0F);
        if (s.isBaby) {
            ps.scale(0.75F, 0.75F, 0.75F);
        }
        ps.mulPose(Axis.ZP.rotation(s.headRollAngle));
        ps.mulPose(Axis.YP.rotationDegrees(s.yRot));
        ps.mulPose(Axis.XP.rotationDegrees(s.xRot));
        if (s.isBaby) {
            ps.translate(s.isSleeping ? 0.4F : 0.06F, 0.26F, s.isSleeping ? 0.15F : -0.5F);
        } else {
            ps.translate(s.isSleeping ? 0.46F : 0.06F, s.isSleeping ? 0.26F : 0.27F, s.isSleeping ? 0.22F : -0.5F);
        }
        ps.mulPose(Axis.XP.rotationDegrees(90.0F));
        if (s.isSleeping) {
            ps.mulPose(Axis.ZP.rotationDegrees(90.0F));
        }
        return new Matrix4f(ps.last().pose());
    };
    private static final LivingEntityVisual.Rotations PANDA_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot, entityScale, 90.0F);
        PandaRenderState s = (PandaRenderState) state;
        if (s.rollTime > 0.0F) {
            float t = Mth.frac(s.rollTime);
            int p = Mth.floor(s.rollTime);
            int n = p + 1;
            float y = s.isBaby ? 0.3F : 0.8F;
            if (p < 8.0F) {
                float a = pandaRollAngle(90.0F * p / 7.0F, 90.0F * n / 7.0F, n, t, 8.0F);
                pose.translate(0.0F, (y + 0.2F) * (a / 90.0F), 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(-a));
            } else if (p < 16.0F) {
                float a = pandaRollAngle(90.0F + 90.0F * (p - 8.0F) / 7.0F, 90.0F + 90.0F * (n - 8.0F) / 7.0F, n, t,
                        16.0F);
                pose.translate(0.0F, y + 0.2F + (y - 0.2F) * (a - 90.0F) / 90.0F, 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(-a));
            } else if (p < 24.0F) {
                float a = pandaRollAngle(180.0F + 90.0F * (p - 16.0F) / 7.0F, 180.0F + 90.0F * (n - 16.0F) / 7.0F, n, t,
                        24.0F);
                pose.translate(0.0F, y + y * (270.0F - a) / 90.0F, 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(-a));
            } else if (p < 32) {
                float a = pandaRollAngle(270.0F + 90.0F * (p - 24.0F) / 7.0F, 270.0F + 90.0F * (n - 24.0F) / 7.0F, n, t,
                        32.0F);
                pose.translate(0.0F, y * ((360.0F - a) / 90.0F), 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(-a));
            }
        }
        if (s.sitAmount > 0.0F) {
            pose.translate(0.0F, 0.8F * s.sitAmount, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(s.sitAmount, s.xRot, s.xRot + 90.0F)));
            pose.translate(0.0F, -1.0F * s.sitAmount, 0.0F);
            if (s.isScared) {
                pose.mulPose(Axis.YP.rotationDegrees((float) (Math.cos(s.ageInTicks * 1.25F) * Math.PI * 0.05F)));
                if (s.isBaby) {
                    pose.translate(0.0F, 0.8F, 0.55F);
                }
            }
        }
        if (s.lieOnBackAmount > 0.0F) {
            float y = s.isBaby ? 0.5F : 1.3F;
            pose.translate(0.0F, y * s.lieOnBackAmount, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(s.lieOnBackAmount, s.xRot, s.xRot + 180.0F)));
        }
    };
    private static final LivingEntityVisual.ItemPose PANDA_HELD = (model, state) -> {
        PandaRenderState s = (PandaRenderState) state;
        float z = -0.6F;
        float y = 1.4F;
        if (s.isEating) {
            z -= 0.2F * Mth.sin(s.ageInTicks * 0.6F) + 0.2F;
            y -= 0.09F * Mth.sin(s.ageInTicks * 0.6F);
        }
        PoseStack ps = new PoseStack();
        ps.translate(0.1F, y, z);
        return new Matrix4f(ps.last().pose());
    };
    private static final Predicate<LivingEntityRenderState> PANDA_HOLDING = s -> {
        PandaRenderState p = (PandaRenderState) s;
        return p.isSitting && !p.isScared;
    };
    private static final Function<LivingEntityRenderState, Identifier> HORSE_MARKINGS = s -> {
        HorseRenderState hs = (HorseRenderState) s;
        return switch (hs.markings) {
            case WHITE -> horseMark("white", hs.isBaby);
            case WHITE_FIELD -> horseMark("whitefield", hs.isBaby);
            case WHITE_DOTS -> horseMark("whitedots", hs.isBaby);
            case BLACK_DOTS -> horseMark("blackdots", hs.isBaby);
            default -> null;
        };
    };
    private static final Map<Identifier, VillagerMetadataSection.Hat> VILLAGER_HAT_FLAGS = new ConcurrentHashMap<>();
    private static final Predicate<LivingEntityRenderState> VILLAGER_TYPE_HAT_VISIBLE = villagerTypeHatVisible(
            "villager");
    private static final Predicate<LivingEntityRenderState> ZOMBIE_VILLAGER_TYPE_HAT_VISIBLE = villagerTypeHatVisible(
            "zombie_villager");
    private static final Predicate<LivingEntityRenderState> EQUINE_RIDDEN = s -> ((EquineRenderState) s).isRidden;
    private static final Predicate<LivingEntityRenderState> CAMEL_RIDDEN = s -> ((CamelRenderState) s).isRidden;
    private static final Predicate<LivingEntityRenderState> IS_TRADER_LLAMA = s -> ((LlamaRenderState) s).isTraderLlama;
    private static final LivingEntityVisual.Shaking ZOMBIE_CONVERTING = s -> s.isFullyFrozen || ((ZombieRenderState) s).isConverting;
    private static final LivingEntityVisual.Shaking HOGLIN_CONVERTING = s -> s.isFullyFrozen || ((HoglinRenderState) s).isConverting;
    private static final LivingEntityVisual.Shaking PIGLIN_CONVERTING = s -> s.isFullyFrozen || ((PiglinRenderState) s).isConverting;
    private static final LivingEntityVisual.Shaking STRIDER_SHAKING = s -> s.isFullyFrozen || ((StriderRenderState) s).isSuffocating;
    private static final LivingEntityVisual.Shaking SKELETON_SHAKING = s -> ((SkeletonRenderState) s).isShaking;
    private static final Identifier WOLF_CRACK_LOW = Identifier.withDefaultNamespace(
            "textures/entity/wolf/wolf_armor_crackiness_low.png");
    private static final Identifier WOLF_CRACK_MEDIUM = Identifier.withDefaultNamespace(
            "textures/entity/wolf/wolf_armor_crackiness_medium.png");
    private static final Identifier WOLF_CRACK_HIGH = Identifier.withDefaultNamespace(
            "textures/entity/wolf/wolf_armor_crackiness_high.png");
    private static final Function<ItemStack, Identifier> WOLF_ARMOR_CRACK = item -> switch (Crackiness.WOLF_ARMOR.byDamage(
            item)) {
        case LOW -> WOLF_CRACK_LOW;
        case MEDIUM -> WOLF_CRACK_MEDIUM;
        case HIGH -> WOLF_CRACK_HIGH;
        default -> null;
    };
    private static final LivingEntityVisual.Rotations ARMOR_STAND_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
        float wiggle = ((ArmorStandRenderState) state).wiggle;
        if (wiggle < 5.0F) {
            pose.mulPose(Axis.YP.rotationDegrees(Mth.sin(wiggle / 1.5F * (float) Math.PI) * 3.0F));
        }
    };
    private static final LivingEntityVisual.Config ARMOR_STAND_CFG = armorStandCfg(ModelLayers.ARMOR_STAND,
            ModelLayers.ARMOR_STAND_ARMOR, ModelLayers.ELYTRA);
    private static final LivingEntityVisual.Config ARMOR_STAND_SMALL_CFG = armorStandCfg(ModelLayers.ARMOR_STAND_SMALL,
            ModelLayers.ARMOR_STAND_SMALL_ARMOR, ModelLayers.ELYTRA_BABY);
    private static final Map<CowVariant.ModelType, LivingEntityVisual.Config[]> COW_CFGS = Map.of(
            CowVariant.ModelType.NORMAL, agePair(
                    cfg(ModelLayers.COW).modelFactory(CowModel::new),
                    cfg(ModelLayers.COW_BABY).modelFactory(CowModel::new)),
            CowVariant.ModelType.WARM, agePair(
                    cfg(ModelLayers.WARM_COW).modelFactory(CowModel::new),
                    cfg(ModelLayers.WARM_COW_BABY).modelFactory(CowModel::new)),
            CowVariant.ModelType.COLD, agePair(
                    cfg(ModelLayers.COLD_COW).modelFactory(CowModel::new),
                    cfg(ModelLayers.COLD_COW_BABY).modelFactory(CowModel::new)));
    private static final Map<PigVariant.ModelType, LivingEntityVisual.Config[]> PIG_CFGS = Map.of(
            PigVariant.ModelType.NORMAL, agePair(
                    pigAdult(ModelLayers.PIG, PigModel::new),
                    cfg(ModelLayers.PIG_BABY).modelFactory(BabyPigModel::new)),
            PigVariant.ModelType.COLD, agePair(
                    pigAdult(ModelLayers.COLD_PIG, ColdPigModel::new),
                    cfg(ModelLayers.PIG_BABY).modelFactory(BabyPigModel::new)));
    private static final Map<ChickenVariant.ModelType, LivingEntityVisual.Config[]> CHICKEN_CFGS = Map.of(
            ChickenVariant.ModelType.NORMAL, agePair(
                    cfg(ModelLayers.CHICKEN).modelFactory(AdultChickenModel::new),
                    cfg(ModelLayers.CHICKEN_BABY).modelFactory(BabyChickenModel::new)),
            ChickenVariant.ModelType.COLD, agePair(
                    cfg(ModelLayers.COLD_CHICKEN).modelFactory(ColdChickenModel::new),
                    cfg(ModelLayers.CHICKEN_BABY).modelFactory(BabyChickenModel::new)));
    private static final LivingEntityVisual.Rotations SHULKER_ROTATIONS = (pose, state, bodyRot, entityScale) -> {
        LivingEntityVisual.baseRotations(pose, state, bodyRot + 180.0F, entityScale, 90.0F);
        pose.rotateAround(((ShulkerRenderState) state).attachFace.getOpposite().getRotation(), 0.0F, 0.5F, 0.0F);
    };
    private static final Function<LivingEntityRenderState, Identifier> SHULKER_TEXTURE =
            s -> ShulkerRenderer.getTextureLocation(((ShulkerRenderState) s).color);
    private static final Predicate<LivingEntity> GUARDIAN_BEAMING = e -> ((Guardian) e).hasActiveAttackTarget();
    private static final Identifier CREAKING_EYES_TEX = Identifier.withDefaultNamespace(
            "textures/entity/creaking/creaking_eyes.png");
    private static final Predicate<LivingEntityRenderState> CREAKING_EYES_GLOWING = s -> ((CreakingRenderState) s).eyesGlowing;
    private static final Function<LivingEntityRenderState, Identifier> COPPER_GOLEM_TEXTURE =
            s -> CopperGolemOxidationLevels.getOxidationLevel(((CopperGolemRenderState) s).weathering).texture();
    private static final Function<LivingEntityRenderState, Identifier> COPPER_GOLEM_EYES =
            s -> CopperGolemOxidationLevels.getOxidationLevel(((CopperGolemRenderState) s).weathering).eyeTexture();
    private static final Function<LivingEntity, BlockState> COPPER_GOLEM_ANTENNA = e -> {
        ItemStack stack = e.getItemBySlot(CopperGolem.EQUIPMENT_SLOT_ANTENNA);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                    .apply(blockItem.getBlock().defaultBlockState());
    };
    private static final Matrix4fc COPPER_ANTENNA_BLOCK = new Matrix4f()
            .translate(0.0F, -1.75F, 0.0F)
            .translate(-0.5F, 0.0F, -0.5F)
            .rotateAround(Axis.ZP.rotationDegrees(180.0F), 0.5F, 0.5F, 0.5F);
    private static final Identifier GHAST_ROPES_TEX = Identifier.withDefaultNamespace(
            "textures/entity/ghast/happy_ghast_ropes.png");

    private static final Predicate<LivingEntityRenderState> GHAST_ROPED = s -> {
        HappyGhastRenderState h = (HappyGhastRenderState) s;
        return h.isLeashHolder && h.bodyItem.is(ItemTags.HARNESSES);
    };
    private static final Predicate<LivingEntityRenderState> GHAST_IS_RIDDEN = s -> ((HappyGhastRenderState) s).isRidden;
    private static final LivingEntityVisual.RiddenPose GHAST_GOGGLES = new LivingEntityVisual.RiddenPose("goggles",
            PartPose.offset(0.0F, 14.0F, -5.5F),
            PartPose.offsetAndRotation(0.0F, 9.0F, -5.5F, -0.7854F, 0.0F, 0.0F));
    private static final LivingEntityVisual.ShadowRadius CUBE_SHADOW = e -> ((AbstractCubeMob) e).getSize() * 0.25F;
    private static final LivingEntityVisual.RootScale SULFUR_CUBE_SCALE = (state, pose) -> {
        SulfurCubeRenderState s = (SulfurCubeRenderState) state;
        pose.scale(0.999F, 0.999F, 0.999F);
        pose.translate(0.0F, 0.001F, 0.0F);
        float size = s.size;
        float squish = s.containedBlock.isEmpty() ? s.squish / (size * 0.5F + 1.0F) : 0.0F;
        float w = 1.0F / (squish + 1.0F);
        pose.scale(w * size, 1.0F / w * size, w * size);
        float fuse = s.fuseRemainingTicks;
        if (fuse < 10.0F && fuse > 0.0F) {
            float swell = 1.0F + TntRenderer.getSwellAmount(fuse);
            pose.scale(swell, swell, swell);
        }
        pose.scale(0.5F, 0.5F, 0.5F);
        pose.translate(0.0F, 0.98F - (s.isInvisible ? 0.0F : 1.0F) / 16.0F, 0.0F);
    };
    private static final LivingEntityVisual.WhiteOverlay SULFUR_FUSE_FLASH = s -> {
        float fuse = ((SulfurCubeRenderState) s).fuseRemainingTicks;
        return fuse > 0.0F && TntRenderer.isLit(fuse) ? 1.0F : 0.0F;
    };
    private static final Identifier SULFUR_INNER_TEX = Identifier.withDefaultNamespace(
            "textures/entity/sulfur_cube/sulfur_cube_inner.png");
    private static final Predicate<LivingEntityRenderState> SULFUR_INNER_VISIBLE =
            s -> ((SulfurCubeRenderState) s).containedBlock.isEmpty();
    private static final Function<LivingEntity, BlockState> SULFUR_CONTAINED = e -> {
        ItemStack stack = e.getItemBySlot(EquipmentSlot.BODY);
        if (stack.isEmpty()) {
            return null;
        }
        Block block = Block.byItem(stack.getItem());
        if (block == Blocks.AIR) {
            return null;
        }
        return stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                    .apply(block.defaultBlockState());
    };
    private static final Matrix4fc SULFUR_BLOCK_OFFSET = new Matrix4f()
            .rotateX((float) Math.toRadians(180.0))
            .translate(-0.5F, -0.518F, -0.5F);

    public static void init() {
        builder(BlockEntityTypes.BELL)
                .factory(BellVisual::new)
                .apply(STABLE);

        builder(BlockEntityTypes.SHULKER_BOX)
                .factory(ShulkerBoxVisual::new)
                .apply(STABLE);

        builder(BlockEntityTypes.CHEST)
                .factory(ChestVisual::new)
                .apply(STABLE);

        builder(BlockEntityTypes.TRAPPED_CHEST)
                .factory(ChestVisual::new)
                .apply(STABLE);

        builder(BlockEntityTypes.ENDER_CHEST)
                .factory(ChestVisual::new)
                .apply(STABLE);

        builder(EntityTypes.BLOCK_DISPLAY).factory(BlockDisplayVisual::new)
                                          .apply(STABLE);

        builder(BlockEntityTypes.COPPER_GOLEM_STATUE)
                .factory(CopperGolemStatueVisual::new)
                .apply(EXPERIMENTAL);

        minecart(EntityTypes.CHEST_MINECART, ModelLayers.CHEST_MINECART)
                .apply(STABLE);
        minecart(EntityTypes.COMMAND_BLOCK_MINECART, ModelLayers.COMMAND_BLOCK_MINECART)
                .apply(STABLE);
        minecart(EntityTypes.FURNACE_MINECART, ModelLayers.FURNACE_MINECART)
                .apply(STABLE);
        minecart(EntityTypes.HOPPER_MINECART, ModelLayers.HOPPER_MINECART)
                .apply(STABLE);
        minecart(EntityTypes.MINECART, ModelLayers.MINECART)
                .apply(STABLE);
        minecart(EntityTypes.SPAWNER_MINECART, ModelLayers.SPAWNER_MINECART)
                .apply(STABLE);

        composable(EntityTypes.TNT_MINECART).apply(VanillaVisuals::commonElements)
                                            .with(element(VisualElements.SHADOW).configure(
                                                                                        new ShadowElement.Config(0.7f,
                                                                                                ShadowElement.Config.DEFAULT_STRENGTH))
                                                                                .build())
                                            .with(element(VisualElements.FIRE).build())
                                            .with(element(VisualElements.TNT_MINECART).build())
                                            .apply(VanillaVisuals::experimentalElements)
                                            .build()
                                            .apply(STABLE);

        builder(EntityTypes.ITEM)
                .factory(ItemVisual::new)
                .skipVanillaRender(ItemVisual::isSupported)
                .apply(EXPERIMENTAL);

        itemFrame(EntityTypes.ITEM_FRAME)
                .apply(EXPERIMENTAL);
        itemFrame(EntityTypes.GLOW_ITEM_FRAME)
                .apply(EXPERIMENTAL);

        builder(EntityTypes.ITEM_DISPLAY)
                .factory(ItemDisplayVisual::new)
                .skipVanillaRender(ItemDisplayVisual::shouldVisualize)
                .apply(EXPERIMENTAL);

        builder(EntityTypes.EXPERIENCE_ORB)
                .factory(ExperienceOrbVisual::new)
                .apply(EXPERIMENTAL);

        builder(EntityTypes.FALLING_BLOCK)
                .factory(FallingBlockVisual::new)
                .apply(EXPERIMENTAL);
        builder(EntityTypes.TNT)
                .factory(PrimedTntVisual::new)
                .apply(EXPERIMENTAL);

        thrownItem(EntityTypes.SNOWBALL, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.EGG, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.ENDER_PEARL, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.SPLASH_POTION, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.LINGERING_POTION, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.EXPERIENCE_BOTTLE, 1.0F, false).apply(EXPERIMENTAL);
        thrownItem(EntityTypes.EYE_OF_ENDER, 1.0F, true).apply(EXPERIMENTAL);

        builder(EntityTypes.LEASH_KNOT)
                .factory(LeashKnotVisual::new)
                .apply(EXPERIMENTAL);
        builder(EntityTypes.EVOKER_FANGS)
                .factory(EvokerFangsVisual::new)
                .apply(EXPERIMENTAL);
        builder(EntityTypes.END_CRYSTAL)
                .factory(EndCrystalVisual::new)
                .skipVanillaRender(EndCrystalVisual::isSupported)
                .apply(EXPERIMENTAL);

        builder(EntityTypes.ARROW)
                .factory((ctx, entity, partialTick) -> new ArrowVisual<>(ctx, entity, partialTick,
                        s -> ((TippableArrowRenderState) s).isTipped
                                ? TippableArrowRenderer.TIPPED_ARROW_LOCATION
                                : TippableArrowRenderer.NORMAL_ARROW_LOCATION))
                .apply(EXPERIMENTAL);
        builder(EntityTypes.SPECTRAL_ARROW)
                .factory((ctx, entity, partialTick) -> new ArrowVisual<>(ctx, entity, partialTick,
                        s -> SpectralArrowRenderer.SPECTRAL_ARROW_LOCATION))
                .apply(EXPERIMENTAL);
        builder(EntityTypes.TRIDENT)
                .factory(TridentVisual::new)
                .apply(EXPERIMENTAL);

        living(EntityTypes.CREEPER, cfg(ModelLayers.CREEPER).scale(CREEPER_SCALE).whiteOverlay(CREEPER_WHITE)
                                                            .scrollOverlay(ModelLayers.CREEPER_ARMOR,
                                                                    CREEPER_SWIRL_MATERIAL, CREEPER_SWIRL_TINT,
                                                                    CREEPER_POWERED)).apply(EXPERIMENTAL);
        living(EntityTypes.SPIDER, cfg(ModelLayers.SPIDER).flipDegrees(180.0F)
                                                          .emissiveOverlay(ModelLayers.SPIDER, SPIDER_EYES)).apply(
                EXPERIMENTAL);
        living(EntityTypes.BLAZE, ModelLayers.BLAZE).apply(EXPERIMENTAL);
        living(EntityTypes.IRON_GOLEM, cfg(ModelLayers.IRON_GOLEM).rotations(IRON_GOLEM_ROTATIONS)
                                                                  .coplanarOverlay(ModelLayers.IRON_GOLEM,
                                                                          IRON_CRACK_LOW,
                                                                          crackiness(Crackiness.Level.LOW))
                                                                  .coplanarOverlay(ModelLayers.IRON_GOLEM,
                                                                          IRON_CRACK_MEDIUM,
                                                                          crackiness(Crackiness.Level.MEDIUM))
                                                                  .coplanarOverlay(ModelLayers.IRON_GOLEM,
                                                                          IRON_CRACK_HIGH,
                                                                          crackiness(Crackiness.Level.HIGH))
                                                                  .blockDecoration(IRON_GOLEM_OFFERING,
                                                                          IRON_GOLEM_FLOWER,
                                                                          new LivingEntityVisual.BlockPlacement(
                                                                                  "right_arm", IRON_FLOWER))).apply(
                EXPERIMENTAL);
        living(EntityTypes.SNOW_GOLEM,
                cfg(ModelLayers.SNOW_GOLEM).blockDecoration(SNOW_GOLEM_HAS_PUMPKIN, SNOW_GOLEM_PUMPKIN,
                        new LivingEntityVisual.BlockPlacement("head", PUMPKIN))).apply(EXPERIMENTAL);
        living(EntityTypes.SKELETON,
                cfg(ModelLayers.SKELETON).heldItems().armor(ModelLayers.SKELETON_ARMOR).shaking(SKELETON_SHAKING)
                                         .elytra().headItem()).apply(EXPERIMENTAL);
        living(EntityTypes.STRAY,
                cfg(ModelLayers.STRAY).heldItems().armor(ModelLayers.STRAY_ARMOR).shaking(SKELETON_SHAKING).elytra()
                                      .headItem()
                                      .overlay(ModelLayers.STRAY_OUTER_LAYER, STRAY_CLOTHES)).apply(EXPERIMENTAL);
        living(EntityTypes.SILVERFISH, cfg(ModelLayers.SILVERFISH).flipDegrees(180.0F)).apply(EXPERIMENTAL);
        living(EntityTypes.ENDERMITE, cfg(ModelLayers.ENDERMITE).flipDegrees(180.0F)).apply(EXPERIMENTAL);
        // Vex + allay: entityTranslucent models whose textures carry REAL alpha gradients (ghostly lower body,
        // wispy tail) -- a cutout body renders those texels opaque, so both take the OIT-translucent body.
        living(EntityTypes.VEX, cfg(ModelLayers.VEX).heldItems().translucentBody()).apply(EXPERIMENTAL);
        living(EntityTypes.BAT, ModelLayers.BAT).apply(EXPERIMENTAL);
        living(EntityTypes.GHAST, ModelLayers.GHAST).apply(EXPERIMENTAL);
        living(EntityTypes.ENDERMAN, cfg(ModelLayers.ENDERMAN)
                .emissiveOverlay(ModelLayers.ENDERMAN, ENDERMAN_EYES)
                .dynamicBlock(ENDERMAN_CARRIED, null, ENDERMAN_BLOCK)).apply(EXPERIMENTAL);
        living(EntityTypes.WITCH, cfg(ModelLayers.WITCH)
                .customHeldItem(LivingEntity::getMainHandItem, WITCH_NOSE, ItemDisplayContext.GROUND,
                        s -> ((WitchRenderState) s).isHoldingPotion)).apply(EXPERIMENTAL);
        living(EntityTypes.BREEZE, cfg(ModelLayers.BREEZE)
                .scrollOverlay(ModelLayers.BREEZE_WIND, BREEZE_WIND_MATERIAL, null, null)
                .emissiveOverlay(ModelLayers.BREEZE, BREEZE_EYES)).apply(EXPERIMENTAL);
        living(EntityTypes.GUARDIAN, cfg(ModelLayers.GUARDIAN).vanillaFallback(GUARDIAN_BEAMING)).apply(EXPERIMENTAL);
        living(EntityTypes.VINDICATOR, cfg(ModelLayers.VINDICATOR)
                .heldItems(s -> ((IllagerRenderState) s).isAggressive).headItem()).apply(EXPERIMENTAL);
        living(EntityTypes.PILLAGER, cfg(ModelLayers.PILLAGER).heldItems().headItem()).apply(EXPERIMENTAL);
        living(EntityTypes.EVOKER, cfg(ModelLayers.EVOKER).headItem()).apply(EXPERIMENTAL);
        ageable(EntityTypes.ZOMBIE, baby -> (baby
                ? cfg(ModelLayers.ZOMBIE_BABY).modelFactory(root -> new BabyZombieModel<>(root))
                                              .babyArmor(ModelLayers.ZOMBIE_BABY_ARMOR).elytra(ModelLayers.ELYTRA_BABY)
                : cfg(ModelLayers.ZOMBIE).modelFactory(root -> new ZombieModel<>(root)).armor(ModelLayers.ZOMBIE_ARMOR)
                                         .elytra())
                .heldItems().shaking(ZOMBIE_CONVERTING).headItem()).apply(EXPERIMENTAL);
        ageable(EntityTypes.HUSK, baby -> (baby
                ? cfg(ModelLayers.HUSK_BABY).modelFactory(root -> new BabyZombieModel<>(root))
                                            .babyArmor(ModelLayers.HUSK_BABY_ARMOR).elytra(ModelLayers.ELYTRA_BABY)
                : cfg(ModelLayers.HUSK).modelFactory(root -> new ZombieModel<>(root)).armor(ModelLayers.HUSK_ARMOR)
                                       .elytra())
                .heldItems().shaking(ZOMBIE_CONVERTING).headItem()).apply(EXPERIMENTAL);
        ageable(EntityTypes.DROWNED, baby -> (baby
                ? cfg(ModelLayers.DROWNED_BABY).modelFactory(BabyDrownedModel::new)
                                               .babyArmor(ModelLayers.DROWNED_BABY_ARMOR)
                                               .elytra(ModelLayers.ELYTRA_BABY)
                                               .overlay(ModelLayers.DROWNED_BABY_OUTER_LAYER, DROWNED_OUTER_TEX)
                : cfg(ModelLayers.DROWNED).modelFactory(DrownedModel::new).armor(ModelLayers.DROWNED_ARMOR).elytra()
                                          .overlay(ModelLayers.DROWNED_OUTER_LAYER, DROWNED_OUTER_TEX))
                .rotations(DROWNED_ROTATIONS).heldItems().headItem()).apply(EXPERIMENTAL);
        ageable(EntityTypes.ZOMBIFIED_PIGLIN, baby -> (baby
                ? cfg(ModelLayers.ZOMBIFIED_PIGLIN_BABY).modelFactory(BabyZombifiedPiglinModel::new)
                                                        .babyArmor(ModelLayers.ZOMBIFIED_PIGLIN_BABY_ARMOR)
                                                        .elytra(ModelLayers.ELYTRA_BABY)
                : cfg(ModelLayers.ZOMBIFIED_PIGLIN).modelFactory(AdultZombifiedPiglinModel::new)
                                                   .armor(ModelLayers.ZOMBIFIED_PIGLIN_ARMOR).elytra())
                .heldItems().headItem(PiglinRenderer.PIGLIN_CUSTOM_HEAD_TRANSFORMS)).apply(EXPERIMENTAL);
        ageable(EntityTypes.SQUID, baby ->
                cfg(baby ? ModelLayers.SQUID_BABY : ModelLayers.SQUID).modelFactory(SquidModel::new)
                                                                      .rotations(SQUID_ROTATIONS)).apply(EXPERIMENTAL);
        ageable(EntityTypes.GLOW_SQUID, baby ->
                cfg(baby ? ModelLayers.SQUID_BABY : ModelLayers.SQUID).modelFactory(SquidModel::new)
                                                                      .rotations(SQUID_ROTATIONS)).apply(EXPERIMENTAL);
        living(EntityTypes.SLIME, cfg(ModelLayers.SLIME).scale(CUBE_SCALE).shadowRadius(CUBE_SHADOW)
                                                        .translucentOverlay(ModelLayers.SLIME_OUTER,
                                                                SLIME_TEXTURE)).apply(EXPERIMENTAL);
        living(EntityTypes.MAGMA_CUBE, cfg(ModelLayers.MAGMA_CUBE).scale(CUBE_SCALE).shadowRadius(CUBE_SHADOW)).apply(
                EXPERIMENTAL);

        living(EntityTypes.WITHER, cfg(ModelLayers.WITHER).scale(WITHER_SCALE)).apply(EXPERIMENTAL);
        living(EntityTypes.PHANTOM, cfg(ModelLayers.PHANTOM).scale(PHANTOM_SCALE).rotations(PHANTOM_ROTATIONS)
                                                            .emissiveOverlay(ModelLayers.PHANTOM, PHANTOM_EYES)).apply(
                EXPERIMENTAL);
        living(EntityTypes.WITHER_SKELETON,
                cfg(ModelLayers.WITHER_SKELETON).heldItems().armor(ModelLayers.WITHER_SKELETON_ARMOR)
                                                .shaking(SKELETON_SHAKING).elytra().headItem()).apply(EXPERIMENTAL);
        living(EntityTypes.CAVE_SPIDER, cfg(ModelLayers.CAVE_SPIDER).flipDegrees(180.0F)
                                                                    .emissiveOverlay(ModelLayers.CAVE_SPIDER,
                                                                            SPIDER_EYES)).apply(EXPERIMENTAL);
        ageable(EntityTypes.PIGLIN, baby -> (baby
                ? cfg(ModelLayers.PIGLIN_BABY).modelFactory(BabyPiglinModel::new)
                                              .babyArmor(ModelLayers.PIGLIN_BABY_ARMOR).elytra(ModelLayers.ELYTRA_BABY)
                : cfg(ModelLayers.PIGLIN).modelFactory(AdultPiglinModel::new).armor(ModelLayers.PIGLIN_ARMOR).elytra())
                .heldItems().shaking(PIGLIN_CONVERTING)
                .headItem(PiglinRenderer.PIGLIN_CUSTOM_HEAD_TRANSFORMS)).apply(EXPERIMENTAL);
        living(EntityTypes.PIGLIN_BRUTE, cfg(ModelLayers.PIGLIN_BRUTE).heldItems().armor(ModelLayers.PIGLIN_BRUTE_ARMOR)
                                                                      .shaking(PIGLIN_CONVERTING).elytra()
                                                                      .headItem(
                                                                              PiglinRenderer.PIGLIN_CUSTOM_HEAD_TRANSFORMS)).apply(
                EXPERIMENTAL);
        living(EntityTypes.BOGGED,
                cfg(ModelLayers.BOGGED).heldItems().armor(ModelLayers.BOGGED_ARMOR).shaking(SKELETON_SHAKING).elytra()
                                       .headItem()
                                       .overlay(ModelLayers.BOGGED_OUTER_LAYER, BOGGED_OUTER_TEX)).apply(EXPERIMENTAL);
        living(EntityTypes.WARDEN, cfg(ModelLayers.WARDEN)
                .emissiveTranslucentOverlay(ModelLayers.WARDEN_BIOLUMINESCENT, WARDEN_BIOLUMINESCENT_TEX, s -> -1, null)
                .emissiveTranslucentOverlay(ModelLayers.WARDEN_PULSATING_SPOTS, WARDEN_SPOTS_1_TEX,
                        s -> ARGB.white(wardenSpots1(s)), s -> wardenSpots1(s) > 1.0E-5F)
                .emissiveTranslucentOverlay(ModelLayers.WARDEN_PULSATING_SPOTS, WARDEN_SPOTS_2_TEX,
                        s -> ARGB.white(wardenSpots2(s)), s -> wardenSpots2(s) > 1.0E-5F)
                .emissiveTranslucentOverlay(ModelLayers.WARDEN_TENDRILS, WARDEN_BASE_TEX,
                        s -> ARGB.white(((WardenRenderState) s).tendrilAnimation),
                        s -> ((WardenRenderState) s).tendrilAnimation > 1.0E-5F)
                .emissiveTranslucentOverlay(ModelLayers.WARDEN_HEART, WARDEN_HEART_TEX,
                        s -> ARGB.white(((WardenRenderState) s).heartAnimation),
                        s -> ((WardenRenderState) s).heartAnimation > 1.0E-5F)).apply(EXPERIMENTAL);
        living(EntityTypes.ZOGLIN, ModelLayers.ZOGLIN).apply(EXPERIMENTAL);
        living(EntityTypes.RAVAGER, ModelLayers.RAVAGER).apply(EXPERIMENTAL);
        living(EntityTypes.TADPOLE, ModelLayers.TADPOLE).apply(EXPERIMENTAL);

        living(EntityTypes.COW,
                (Cow e) -> COW_CFGS.get(e.getVariant().value().modelAndTexture().model())[e.isBaby() ? 1 : 0]).apply(
                EXPERIMENTAL);
        living(EntityTypes.PIG,
                (Pig e) -> PIG_CFGS.get(e.getVariant().value().modelAndTexture().model())[e.isBaby() ? 1 : 0]).apply(
                EXPERIMENTAL);
        living(EntityTypes.CHICKEN, (Chicken e) -> CHICKEN_CFGS.get(
                e.getVariant().value().modelAndTexture().model())[e.isBaby() ? 1 : 0]).apply(EXPERIMENTAL);
        ageable(EntityTypes.MOOSHROOM, baby -> baby
                ? cfg(ModelLayers.MOOSHROOM_BABY).modelFactory(BabyCowModel::new)
                : cfg(ModelLayers.MOOSHROOM).modelFactory(CowModel::new).blockDecoration(MOOSHROOM_MUSHROOM,
                new LivingEntityVisual.BlockPlacement(null, MUSHROOM_1),
                new LivingEntityVisual.BlockPlacement(null, MUSHROOM_2),
                new LivingEntityVisual.BlockPlacement("head", MUSHROOM_3))).apply(EXPERIMENTAL);
        ageable(EntityTypes.TURTLE, baby -> (baby
                ? cfg(ModelLayers.TURTLE_BABY).modelFactory(BabyTurtleModel::new)
                : cfg(ModelLayers.TURTLE).modelFactory(AdultTurtleModel::new))
                .shadowRadius(TURTLE_SHADOW)).apply(EXPERIMENTAL);
        ageable(EntityTypes.GOAT, baby -> baby
                ? cfg(ModelLayers.GOAT_BABY).modelFactory(BabyGoatModel::new)
                : cfg(ModelLayers.GOAT).modelFactory(GoatModel::new)).apply(EXPERIMENTAL);
        ageable(EntityTypes.POLAR_BEAR, baby ->
                cfg(baby ? ModelLayers.POLAR_BEAR_BABY : ModelLayers.POLAR_BEAR).modelFactory(
                        PolarBearModel::new)).apply(EXPERIMENTAL);
        ageable(EntityTypes.HOGLIN, baby -> (baby
                ? cfg(ModelLayers.HOGLIN_BABY).modelFactory(BabyHoglinModel::new)
                : cfg(ModelLayers.HOGLIN).modelFactory(HoglinModel::new))
                .shaking(HOGLIN_CONVERTING)).apply(EXPERIMENTAL);
        ageable(EntityTypes.ZOGLIN, baby -> baby
                ? cfg(ModelLayers.ZOGLIN_BABY).modelFactory(BabyHoglinModel::new)
                : cfg(ModelLayers.ZOGLIN).modelFactory(HoglinModel::new)).apply(EXPERIMENTAL);
        ageable(EntityTypes.ARMADILLO, baby -> baby
                ? cfg(ModelLayers.ARMADILLO_BABY).modelFactory(BabyArmadilloModel::new)
                : cfg(ModelLayers.ARMADILLO).modelFactory(AdultArmadilloModel::new)).apply(EXPERIMENTAL);
        ageable(EntityTypes.SNIFFER, baby ->
                cfg(baby ? ModelLayers.SNIFFER_BABY : ModelLayers.SNIFFER).modelFactory(SnifferModel::new)).apply(
                EXPERIMENTAL);
        ageable(EntityTypes.RABBIT, baby -> baby
                ? cfg(ModelLayers.RABBIT_BABY).modelFactory(BabyRabbitModel::new)
                : cfg(ModelLayers.RABBIT).modelFactory(AdultRabbitModel::new)).apply(EXPERIMENTAL);
        living(EntityTypes.PARROT, ModelLayers.PARROT).apply(EXPERIMENTAL);
        ageable(EntityTypes.AXOLOTL, baby -> baby
                ? cfg(ModelLayers.AXOLOTL_BABY).modelFactory(BabyAxolotlModel::new)
                : cfg(ModelLayers.AXOLOTL).modelFactory(AdultAxolotlModel::new)).apply(EXPERIMENTAL);
        living(EntityTypes.FROG, ModelLayers.FROG).apply(EXPERIMENTAL);
        living(EntityTypes.COD, cfg(ModelLayers.COD).rotations(COD_ROTATIONS)).apply(EXPERIMENTAL);
        ageable(EntityTypes.DOLPHIN, baby ->
                cfg(baby ? ModelLayers.DOLPHIN_BABY : ModelLayers.DOLPHIN).modelFactory(DolphinModel::new)).apply(
                EXPERIMENTAL);
        living(EntityTypes.SALMON, (Salmon e) -> switch (e.getVariant()) {
            case SMALL -> SALMON_SMALL_CFG;
            case MEDIUM -> SALMON_MEDIUM_CFG;
            case LARGE -> SALMON_LARGE_CFG;
        }).apply(EXPERIMENTAL);
        living(EntityTypes.TROPICAL_FISH, (TropicalFish e) -> switch (e.getPattern().base()) {
            case SMALL -> TROPICAL_SMALL_CFG;
            case LARGE -> TROPICAL_LARGE_CFG;
        }).apply(EXPERIMENTAL);
        living(EntityTypes.PUFFERFISH, cfg(ModelLayers.PUFFERFISH_BIG)
                .modelVariants(PUFFER_STATE,
                        new LivingEntityVisual.ModelVariant(ModelLayers.PUFFERFISH_SMALL, PufferfishSmallModel::new),
                        new LivingEntityVisual.ModelVariant(ModelLayers.PUFFERFISH_MEDIUM, PufferfishMidModel::new),
                        new LivingEntityVisual.ModelVariant(ModelLayers.PUFFERFISH_BIG, PufferfishBigModel::new))
                .rotations(PUFFERFISH_ROTATIONS)
                .shadowRadius(PUFFERFISH_SHADOW)).apply(EXPERIMENTAL);
        ageable(EntityTypes.NAUTILUS, baby -> baby
                ? cfg(ModelLayers.NAUTILUS_BABY).modelFactory(NautilusModel::new)
                : cfg(ModelLayers.NAUTILUS).modelFactory(NautilusModel::new)
                                           .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                   EquipmentClientInfo.LayerType.NAUTILUS_BODY,
                                                   ModelLayers.NAUTILUS_ARMOR)
                                           .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                   EquipmentClientInfo.LayerType.NAUTILUS_SADDLE,
                                                   ModelLayers.NAUTILUS_SADDLE)).apply(EXPERIMENTAL);
        living(EntityTypes.ZOMBIE_NAUTILUS,
                (ZombieNautilus e) -> switch (e.getVariant().value().modelAndTexture().model()) {
                    case NORMAL -> ZOMBIE_NAUTILUS_CFG;
                    case WARM -> ZOMBIE_NAUTILUS_CORAL_CFG;
                }).apply(EXPERIMENTAL);

        ageable(EntityTypes.SHEEP, baby -> baby
                ? cfg(ModelLayers.SHEEP_BABY).modelFactory(BabySheepModel::new)
                                             .coloredOverlay(ModelLayers.SHEEP_BABY_WOOL, SHEEP_BABY_WOOL_TEX,
                                                     SHEEP_WOOL_COLOR, s -> !((SheepRenderState) s).isSheared)
                : cfg(ModelLayers.SHEEP).modelFactory(SheepModel::new)
                                        .coloredOverlay(ModelLayers.SHEEP_WOOL, SHEEP_WOOL_TEX, SHEEP_WOOL_COLOR,
                                                s -> !((SheepRenderState) s).isSheared)
                                        .coloredOverlay(ModelLayers.SHEEP_WOOL_UNDERCOAT, SHEEP_UNDERCOAT_TEX,
                                                SHEEP_WOOL_COLOR, SHEEP_UNDERCOAT_VISIBLE)).apply(EXPERIMENTAL);
        ageable(EntityTypes.WOLF, baby -> baby
                ? cfg(ModelLayers.WOLF_BABY).modelFactory(BabyWolfModel::new)
                                            .coloredCoplanarOverlay(ModelLayers.WOLF_BABY, WOLF_BABY_COLLAR_TEX,
                                                    WOLF_COLLAR_COLOR, WOLF_HAS_COLLAR)
                : cfg(ModelLayers.WOLF).modelFactory(AdultWolfModel::new)
                                       .coloredCoplanarOverlay(ModelLayers.WOLF, WOLF_COLLAR_TEX, WOLF_COLLAR_COLOR,
                                               WOLF_HAS_COLLAR)
                                       .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                               EquipmentClientInfo.LayerType.WOLF_BODY, ModelLayers.WOLF_ARMOR, null,
                                               WOLF_ARMOR_CRACK)).apply(EXPERIMENTAL);
        ageable(EntityTypes.CAT, baby -> (baby
                ? cfg(ModelLayers.CAT_BABY).modelFactory(BabyCatModel::new)
                                           .coloredCoplanarOverlay(ModelLayers.CAT_BABY_COLLAR, CAT_BABY_COLLAR_TEX,
                                                   CAT_COLLAR_COLOR, CAT_HAS_COLLAR)
                : cfg(ModelLayers.CAT).modelFactory(AdultCatModel::new)
                                      .coloredCoplanarOverlay(ModelLayers.CAT_COLLAR, CAT_COLLAR_TEX, CAT_COLLAR_COLOR,
                                              CAT_HAS_COLLAR))
                .rotations(CAT_ROTATIONS)).apply(EXPERIMENTAL);
        ageable(EntityTypes.OCELOT, baby -> baby
                ? cfg(ModelLayers.OCELOT_BABY).modelFactory(BabyOcelotModel::new)
                : cfg(ModelLayers.OCELOT).modelFactory(AdultOcelotModel::new)).apply(EXPERIMENTAL);
        ageable(EntityTypes.BEE, baby -> baby
                ? cfg(ModelLayers.BEE_BABY).modelFactory(BabyBeeModel::new)
                : cfg(ModelLayers.BEE).modelFactory(AdultBeeModel::new)).apply(EXPERIMENTAL);

        ageable(EntityTypes.FOX, baby -> (baby
                ? cfg(ModelLayers.FOX_BABY).modelFactory(BabyFoxModel::new)
                : cfg(ModelLayers.FOX).modelFactory(AdultFoxModel::new))
                .rotations(FOX_ROTATIONS)
                .customHeldItem(LivingEntity::getMainHandItem, FOX_MOUTH, ItemDisplayContext.GROUND, null)).apply(
                EXPERIMENTAL);
        ageable(EntityTypes.PANDA, baby -> (baby
                ? cfg(ModelLayers.PANDA_BABY).modelFactory(BabyPandaModel::new)
                : cfg(ModelLayers.PANDA).modelFactory(PandaModel::new))
                .rotations(PANDA_ROTATIONS)
                .customHeldItem(LivingEntity::getMainHandItem, PANDA_HELD, ItemDisplayContext.GROUND,
                        PANDA_HOLDING)).apply(EXPERIMENTAL);
        ageable(EntityTypes.HORSE, baby -> baby
                ? cfg(ModelLayers.HORSE_BABY).modelFactory(BabyHorseModel::new)
                                             .texturedTranslucentOverlay(ModelLayers.HORSE_BABY, HORSE_MARKINGS, null)
                : cfg(ModelLayers.HORSE).modelFactory(HorseModel::new)
                                        .texturedTranslucentOverlay(ModelLayers.HORSE, HORSE_MARKINGS, null)
                                        .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                EquipmentClientInfo.LayerType.HORSE_BODY, ModelLayers.HORSE_ARMOR)
                                        .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                EquipmentClientInfo.LayerType.HORSE_SADDLE, ModelLayers.HORSE_SADDLE,
                                                EQUINE_RIDDEN, null)).apply(EXPERIMENTAL);
        ageable(EntityTypes.DONKEY, baby -> baby
                ? cfg(ModelLayers.DONKEY_BABY).modelFactory(BabyDonkeyModel::new)
                : cfg(ModelLayers.DONKEY).modelFactory(DonkeyModel::new)
                                         .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                 EquipmentClientInfo.LayerType.DONKEY_SADDLE, ModelLayers.DONKEY_SADDLE,
                                                 EQUINE_RIDDEN, null)).apply(EXPERIMENTAL);
        ageable(EntityTypes.MULE, baby -> baby
                ? cfg(ModelLayers.MULE_BABY).modelFactory(BabyDonkeyModel::new)
                : cfg(ModelLayers.MULE).modelFactory(DonkeyModel::new)
                                       .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                               EquipmentClientInfo.LayerType.MULE_SADDLE, ModelLayers.MULE_SADDLE,
                                               EQUINE_RIDDEN, null)).apply(EXPERIMENTAL);
        ageable(EntityTypes.SKELETON_HORSE, baby -> baby
                ? cfg(ModelLayers.SKELETON_HORSE_BABY).modelFactory(BabyHorseModel::new)
                : cfg(ModelLayers.SKELETON_HORSE).modelFactory(HorseModel::new)
                                                 .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                         EquipmentClientInfo.LayerType.HORSE_BODY,
                                                         ModelLayers.UNDEAD_HORSE_ARMOR)
                                                 .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                         EquipmentClientInfo.LayerType.SKELETON_HORSE_SADDLE,
                                                         ModelLayers.SKELETON_HORSE_SADDLE, EQUINE_RIDDEN, null)).apply(
                EXPERIMENTAL);
        ageable(EntityTypes.ZOMBIE_HORSE, baby -> baby
                ? cfg(ModelLayers.ZOMBIE_HORSE_BABY).modelFactory(BabyHorseModel::new)
                : cfg(ModelLayers.ZOMBIE_HORSE).modelFactory(HorseModel::new)
                                               .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                       EquipmentClientInfo.LayerType.HORSE_BODY,
                                                       ModelLayers.UNDEAD_HORSE_ARMOR)
                                               .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                       EquipmentClientInfo.LayerType.ZOMBIE_HORSE_SADDLE,
                                                       ModelLayers.ZOMBIE_HORSE_SADDLE, EQUINE_RIDDEN, null)).apply(
                EXPERIMENTAL);
        // Villager: the biome TYPE texture is clothing-only -- an overlay over the base skin, not the body texture.
        // The type overlay is a complementary pair reproducing the no-hat model swap (mcmeta hat flags).
        ageable(EntityTypes.VILLAGER, baby -> (baby
                ? cfg(ModelLayers.VILLAGER_BABY).modelFactory(BabyVillagerModel::new)
                                                .headItem(VillagerRenderer.CUSTOM_HEAD_TRANSFORMS)
                                                .texturedCoplanarOverlay(ModelLayers.VILLAGER_BABY,
                                                        villagerType("villager", "baby"), VILLAGER_TYPE_HAT_VISIBLE)
                                                .texturedCoplanarOverlay(ModelLayers.VILLAGER_BABY_NO_HAT,
                                                        villagerType("villager", "baby"),
                                                        VILLAGER_TYPE_HAT_VISIBLE.negate())
                : cfg(ModelLayers.VILLAGER).modelFactory(VillagerModel::new)
                                           .headItem(VillagerRenderer.CUSTOM_HEAD_TRANSFORMS)
                                           .texturedCoplanarOverlay(ModelLayers.VILLAGER,
                                                   villagerType("villager", "type"), VILLAGER_TYPE_HAT_VISIBLE)
                                           .texturedCoplanarOverlay(ModelLayers.VILLAGER_NO_HAT,
                                                   villagerType("villager", "type"), VILLAGER_TYPE_HAT_VISIBLE.negate())
                                           .texturedCoplanarOverlay(ModelLayers.VILLAGER,
                                                   villagerProfession("villager"), null)
                                           .texturedCoplanarOverlay(ModelLayers.VILLAGER, villagerLevel("villager"),
                                                   null))
                .shadowRadius(VILLAGER_SHADOW)).apply(EXPERIMENTAL);
        ageable(EntityTypes.ZOMBIE_VILLAGER, baby -> (baby
                ? cfg(ModelLayers.ZOMBIE_VILLAGER_BABY).modelFactory(root -> new BabyZombieVillagerModel<>(root))
                                                       .babyArmor(ModelLayers.ZOMBIE_VILLAGER_BABY_ARMOR)
                                                       .elytra(ModelLayers.ELYTRA_BABY)
                                                       .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER_BABY,
                                                               villagerType("zombie_villager", "baby"),
                                                               ZOMBIE_VILLAGER_TYPE_HAT_VISIBLE)
                                                       .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER_BABY_NO_HAT,
                                                               villagerType("zombie_villager", "baby"),
                                                               ZOMBIE_VILLAGER_TYPE_HAT_VISIBLE.negate())
                : cfg(ModelLayers.ZOMBIE_VILLAGER).modelFactory(root -> new ZombieVillagerModel<>(root))
                                                  .armor(ModelLayers.ZOMBIE_VILLAGER_ARMOR).elytra()
                                                  .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER,
                                                          villagerType("zombie_villager", "type"),
                                                          ZOMBIE_VILLAGER_TYPE_HAT_VISIBLE)
                                                  .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER_NO_HAT,
                                                          villagerType("zombie_villager", "type"),
                                                          ZOMBIE_VILLAGER_TYPE_HAT_VISIBLE.negate())
                                                  .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER,
                                                          villagerProfession("zombie_villager"), null)
                                                  .texturedCoplanarOverlay(ModelLayers.ZOMBIE_VILLAGER,
                                                          villagerLevel("zombie_villager"), null))
                .heldItems().shaking(ZOMBIE_CONVERTING)
                .headItem(VillagerRenderer.CUSTOM_HEAD_TRANSFORMS)).apply(EXPERIMENTAL);

        ageable(EntityTypes.CAMEL, baby -> baby
                ? cfg(ModelLayers.CAMEL_BABY).modelFactory(BabyCamelModel::new)
                : cfg(ModelLayers.CAMEL).modelFactory(AdultCamelModel::new)
                                        .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                EquipmentClientInfo.LayerType.CAMEL_SADDLE, ModelLayers.CAMEL_SADDLE,
                                                CAMEL_RIDDEN, null)).apply(EXPERIMENTAL);
        ageable(EntityTypes.STRIDER, baby -> (baby
                ? cfg(ModelLayers.STRIDER_BABY).modelFactory(BabyStriderModel::new)
                : cfg(ModelLayers.STRIDER).modelFactory(AdultStriderModel::new)
                                          .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                                  EquipmentClientInfo.LayerType.STRIDER_SADDLE,
                                                  ModelLayers.STRIDER_SADDLE))
                .shaking(STRIDER_SHAKING)
                .shadowRadius(STRIDER_SHADOW)).apply(EXPERIMENTAL);
        living(EntityTypes.ALLAY, cfg(ModelLayers.ALLAY).heldItems().translucentBody()).apply(EXPERIMENTAL);
        living(EntityTypes.WANDERING_TRADER, cfg(ModelLayers.WANDERING_TRADER).headItem()).apply(EXPERIMENTAL);
        living(EntityTypes.GIANT, cfg(ModelLayers.GIANT).heldItems().armor(ModelLayers.GIANT_ARMOR)).apply(
                EXPERIMENTAL);

        ageable(EntityTypes.LLAMA, baby -> baby
                ? cfg(ModelLayers.LLAMA_BABY).modelFactory(BabyLlamaModel::new)
                : cfg(ModelLayers.LLAMA).modelFactory(LlamaModel::new)
                                        .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                EquipmentClientInfo.LayerType.LLAMA_BODY, ModelLayers.LLAMA_DECOR,
                                                EquipmentAssets.TRADER_LLAMA, IS_TRADER_LLAMA)).apply(EXPERIMENTAL);
        ageable(EntityTypes.TRADER_LLAMA, baby -> baby
                ? cfg(ModelLayers.TRADER_LLAMA_BABY).modelFactory(BabyLlamaModel::new)
                                                    .bodyEquipment(e -> ItemStack.EMPTY,
                                                            EquipmentClientInfo.LayerType.LLAMA_BODY,
                                                            ModelLayers.LLAMA_BABY_DECOR,
                                                            EquipmentAssets.TRADER_LLAMA_BABY, null)
                : cfg(ModelLayers.TRADER_LLAMA).modelFactory(LlamaModel::new)
                                               .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                       EquipmentClientInfo.LayerType.LLAMA_BODY,
                                                       ModelLayers.LLAMA_DECOR,
                                                       EquipmentAssets.TRADER_LLAMA, IS_TRADER_LLAMA)).apply(
                EXPERIMENTAL);

        // Armor stand: a LivingEntity whose isBaby() IS isSmall(), so the small variant dispatches its own config
        // (handlesBaby); invisible stands keep vanilla via the isInvisible complement.
        living(EntityTypes.ARMOR_STAND, (ArmorStand e) ->
                e.isSmall() ? ARMOR_STAND_SMALL_CFG : ARMOR_STAND_CFG).apply(EXPERIMENTAL);
        living(EntityTypes.SHULKER, cfg(ModelLayers.SHULKER).rotations(SHULKER_ROTATIONS)
                                                            .bodyTexture(SHULKER_TEXTURE)).apply(EXPERIMENTAL);
        living(EntityTypes.ELDER_GUARDIAN, cfg(ModelLayers.ELDER_GUARDIAN).vanillaFallback(GUARDIAN_BEAMING)).apply(
                EXPERIMENTAL);
        living(EntityTypes.CREAKING, cfg(ModelLayers.CREAKING)
                .emissiveOverlay(ModelLayers.CREAKING_EYES, CREAKING_EYES_TEX, CREAKING_EYES_GLOWING)).apply(
                EXPERIMENTAL);
        living(EntityTypes.PARCHED, cfg(ModelLayers.PARCHED).heldItems().armor(ModelLayers.PARCHED_ARMOR)
                                                            .shaking(SKELETON_SHAKING).elytra().headItem()).apply(
                EXPERIMENTAL);
        living(EntityTypes.COPPER_GOLEM, cfg(ModelLayers.COPPER_GOLEM)
                .bodyTexture(COPPER_GOLEM_TEXTURE)
                .texturedEmissiveOverlay(ModelLayers.COPPER_GOLEM, COPPER_GOLEM_EYES, null)
                .heldItems()
                .headItem()
                .dynamicBlock(COPPER_GOLEM_ANTENNA, "/body/head", COPPER_ANTENNA_BLOCK)).apply(EXPERIMENTAL);
        ageable(EntityTypes.HAPPY_GHAST, baby -> (baby
                ? cfg(ModelLayers.HAPPY_GHAST_BABY).modelFactory(HappyGhastModel::new)
                                                   .overlay(ModelLayers.HAPPY_GHAST_BABY_ROPES, GHAST_ROPES_TEX,
                                                           GHAST_ROPED)
                                                   .bodyEquipmentPosed(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                           EquipmentClientInfo.LayerType.HAPPY_GHAST_BODY,
                                                           ModelLayers.HAPPY_GHAST_BABY_HARNESS, GHAST_IS_RIDDEN,
                                                           GHAST_GOGGLES)
                : cfg(ModelLayers.HAPPY_GHAST).modelFactory(HappyGhastModel::new)
                                              .overlay(ModelLayers.HAPPY_GHAST_ROPES, GHAST_ROPES_TEX, GHAST_ROPED)
                                              .bodyEquipmentPosed(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                                      EquipmentClientInfo.LayerType.HAPPY_GHAST_BODY,
                                                      ModelLayers.HAPPY_GHAST_HARNESS, GHAST_IS_RIDDEN,
                                                      GHAST_GOGGLES))).apply(EXPERIMENTAL);
        // Sulfur cube: translucent outer body + inner jelly, the swallowed block as a dynamic block, the fuse
        // swell + TNT-style flash, and the size/squish scale.
        living(EntityTypes.SULFUR_CUBE, cfg(ModelLayers.SULFUR_CUBE).modelFactory(SulfurCubeModel::new)
                                                                    .translucentBody()
                                                                    .scale(SULFUR_CUBE_SCALE)
                                                                    .shadowRadius(CUBE_SHADOW)
                                                                    .whiteOverlay(SULFUR_FUSE_FLASH)
                                                                    .translucentOverlay(ModelLayers.SULFUR_CUBE_INNER,
                                                                            SULFUR_INNER_TEX, SULFUR_INNER_VISIBLE)
                                                                    .dynamicBlock(SULFUR_CONTAINED, null,
                                                                            SULFUR_BLOCK_OFFSET)).apply(EXPERIMENTAL);
    }

    private static Predicate<LivingEntityRenderState> crackiness(Crackiness.Level level) {
        return s -> ((IronGolemRenderState) s).crackiness == level;
    }

    private static LivingEntityVisual.Config salmonCfg(ModelLayerLocation layer) {
        return cfg(layer).modelFactory(SalmonModel::new)
                         .rotations(SALMON_ROTATIONS)
                         .build();
    }

    private static Identifier tropicalPattern(TropicalFish.Pattern pattern) {
        String path = switch (pattern) {
            case KOB -> "tropical_a_pattern_1";
            case SUNSTREAK -> "tropical_a_pattern_2";
            case SNOOPER -> "tropical_a_pattern_3";
            case DASHER -> "tropical_a_pattern_4";
            case BRINELY -> "tropical_a_pattern_5";
            case SPOTTY -> "tropical_a_pattern_6";
            case FLOPPER -> "tropical_b_pattern_1";
            case STRIPEY -> "tropical_b_pattern_2";
            case GLITTER -> "tropical_b_pattern_3";
            case BLOCKFISH -> "tropical_b_pattern_4";
            case BETTY -> "tropical_b_pattern_5";
            case CLAYFISH -> "tropical_b_pattern_6";
        };
        return Identifier.withDefaultNamespace("textures/entity/fish/" + path + ".png");
    }

    private static LivingEntityVisual.Config tropicalCfg(boolean small) {
        return cfg(small ? ModelLayers.TROPICAL_FISH_SMALL : ModelLayers.TROPICAL_FISH_LARGE)
                .modelFactory(small ? TropicalFishSmallModel::new : TropicalFishLargeModel::new)
                .rotations(TROPICAL_ROTATIONS)
                .bodyColor(s -> ((TropicalFishRenderState) s).baseColor)
                .texturedColoredCoplanarOverlay(
                        small ? ModelLayers.TROPICAL_FISH_SMALL_PATTERN : ModelLayers.TROPICAL_FISH_LARGE_PATTERN,
                        TROPICAL_PATTERN, s -> ((TropicalFishRenderState) s).patternColor, null)
                .build();
    }

    private static LivingEntityVisual.Config zombieNautilusCfg(ModelLayerLocation layer,
                                                               Function<ModelPart, ? extends EntityModel<?>> factory) {
        return cfg(layer).modelFactory(factory)
                         .bodyTexture(s -> {
                             ZombieNautilusVariant v = ((NautilusRenderState) s).variant;
                             return v == null ? null : v.modelAndTexture().asset().texturePath();
                         })
                         .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.BODY),
                                 EquipmentClientInfo.LayerType.NAUTILUS_BODY, ModelLayers.NAUTILUS_ARMOR)
                         .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                 EquipmentClientInfo.LayerType.NAUTILUS_SADDLE, ModelLayers.NAUTILUS_SADDLE)
                         .build();
    }

    private static float wardenSpots1(LivingEntityRenderState s) {
        return Math.max(0.0F, Mth.cos(s.ageInTicks * 0.045F) * 0.25F);
    }

    private static float wardenSpots2(LivingEntityRenderState s) {
        return Math.max(0.0F, Mth.cos(s.ageInTicks * 0.045F + (float) Math.PI) * 0.25F);
    }

    private static float pandaRollAngle(float thisAngle, float nextAngle, int nextRollPos, float t, float threshold) {
        return nextRollPos < threshold ? Mth.lerp(t, thisAngle, nextAngle) : thisAngle;
    }

    private static Identifier horseMark(String name, boolean baby) {
        return Identifier.withDefaultNamespace(
                "textures/entity/horse/horse_markings_" + name + (baby ? "_baby" : "") + ".png");
    }

    private static Function<LivingEntityRenderState, Identifier> villagerType(String path, String dir) {
        return s -> {
            VillagerData d = ((VillagerDataHolderRenderState) s).getVillagerData();
            return d == null ? null : d.type().unwrapKey().map(k -> villagerTex(path, dir, k.identifier()))
                                       .orElse(null);
        };
    }

    private static Function<LivingEntityRenderState, Identifier> villagerProfession(String path) {
        return s -> {
            VillagerData d = ((VillagerDataHolderRenderState) s).getVillagerData();
            if (d == null || d.profession().is(VillagerProfession.NONE)) {
                return null;
            }
            return d.profession().unwrapKey().map(k -> villagerTex(path, "profession", k.identifier())).orElse(null);
        };
    }

    private static Function<LivingEntityRenderState, Identifier> villagerLevel(String path) {
        return s -> {
            VillagerData d = ((VillagerDataHolderRenderState) s).getVillagerData();
            if (d == null || d.profession().is(VillagerProfession.NONE) || d.profession()
                                                                            .is(VillagerProfession.NITWIT)) {
                return null;
            }
            String name = switch (Mth.clamp(d.level(), 1, 5)) {
                case 1 -> "stone";
                case 2 -> "iron";
                case 3 -> "gold";
                case 4 -> "emerald";
                default -> "diamond";
            };
            return villagerTex(path, "profession_level", Identifier.withDefaultNamespace(name));
        };
    }

    private static Identifier villagerTex(String path, String type, Identifier key) {
        return key.withPath(p -> "textures/entity/" + path + "/" + type + "/" + p + ".png");
    }

    private static VillagerMetadataSection.Hat villagerHat(Identifier texture) {
        return VILLAGER_HAT_FLAGS.computeIfAbsent(texture, tex -> Minecraft.getInstance().getResourceManager()
                                                                           .getResource(tex)
                                                                           .flatMap(resource -> {
                                                                               try {
                                                                                   return resource.metadata()
                                                                                                  .getSection(
                                                                                                          VillagerMetadataSection.TYPE)
                                                                                                  .map(VillagerMetadataSection::hat);
                                                                               } catch (IOException ignored) {
                                                                                   return Optional.empty();
                                                                               }
                                                                           })
                                                                           .orElse(VillagerMetadataSection.Hat.NONE));
    }

    private static Predicate<LivingEntityRenderState> villagerTypeHatVisible(String path) {
        return s -> {
            VillagerData d = ((VillagerDataHolderRenderState) s).getVillagerData();
            if (d == null) {
                return true;
            }
            VillagerMetadataSection.Hat professionHat = d.profession().unwrapKey()
                                                         .map(k -> villagerHat(
                                                                 villagerTex(path, "profession", k.identifier())))
                                                         .orElse(VillagerMetadataSection.Hat.NONE);
            if (professionHat == VillagerMetadataSection.Hat.NONE) {
                return true;
            }
            VillagerMetadataSection.Hat typeHat = d.type().unwrapKey()
                                                   .map(k -> villagerHat(villagerTex(path, "type", k.identifier())))
                                                   .orElse(VillagerMetadataSection.Hat.NONE);
            return professionHat == VillagerMetadataSection.Hat.PARTIAL && typeHat != VillagerMetadataSection.Hat.FULL;
        };
    }

    private static LivingEntityVisual.Config armorStandCfg(ModelLayerLocation layer,
                                                           ArmorModelSet<ModelLayerLocation> armorSet,
                                                           ModelLayerLocation elytraLayer) {
        return cfg(layer).modelFactory(ArmorStandModel::new)
                         .rotations(ARMOR_STAND_ROTATIONS)
                         .handlesBaby()
                         .heldItems()
                         .headItem()
                         .armor(armorSet)
                         .elytra(elytraLayer)
                         .build();
    }

    private static LivingEntityVisual.Config[] agePair(LivingEntityVisual.Config.Builder adult,
                                                       LivingEntityVisual.Config.Builder baby) {
        return new LivingEntityVisual.Config[]{adult.handlesBaby().build(), baby.handlesBaby().build()};
    }

    private static LivingEntityVisual.Config.Builder pigAdult(ModelLayerLocation layer,
                                                              Function<ModelPart, ? extends EntityModel<?>> model) {
        return cfg(layer).modelFactory(model)
                         .bodyEquipment(e -> e.getItemBySlot(EquipmentSlot.SADDLE),
                                 EquipmentClientInfo.LayerType.PIG_SADDLE, ModelLayers.PIG_SADDLE);
    }

    private static LivingEntityVisual.Config.Builder cfg(ModelLayerLocation layer) {
        return LivingEntityVisual.Config.builder(layer);
    }

    public static <T extends LivingEntity> EntityVisualizerBuilder<T> living(EntityType<T> type,
                                                                             ModelLayerLocation layer) {
        return living(type, cfg(layer));
    }

    public static <T extends LivingEntity> EntityVisualizerBuilder<T> living(EntityType<T> type,
                                                                             LivingEntityVisual.Config.Builder config) {
        LivingEntityVisual.Config built = config.build();
        return builder(type)
                // skipVanillaRender must stay the exact complement of the visual's per-frame hide gate
                // (Config.vanillaHandles): babies, invisible entities, and the per-mob fallback (a beaming guardian).
                .factory((ctx, entity, partialTick) -> new LivingEntityVisual<>(ctx, entity, partialTick, built))
                .skipVanillaRender(e -> !built.vanillaHandles(e));
    }

    public static <T extends LivingEntity> EntityVisualizerBuilder<T> living(EntityType<T> type,
                                                                             Function<T, LivingEntityVisual.Config> config) {
        return builder(type)
                .factory((ctx, entity, partialTick) -> new DispatchingLivingEntityVisual<>(ctx, entity, partialTick,
                        config))
                .skipVanillaRender(e -> !config.apply(e).vanillaHandles(e));
    }

    // An ageable mob: adult/baby configs from one per-age customizer, re-dispatched at runtime. Every config MUST
    // bake via modelFactory: AgeableMobRenderer mutates this.model per submit, so renderer.getModel() is unreliable.
    private static <T extends LivingEntity> EntityVisualizerBuilder<T> ageable(EntityType<T> type,
                                                                               Function<Boolean, LivingEntityVisual.Config.Builder> cfg) {
        LivingEntityVisual.Config adult = cfg.apply(false).handlesBaby().build();
        LivingEntityVisual.Config baby = cfg.apply(true).handlesBaby().build();
        return living(type, e -> e.isBaby() ? baby : adult);
    }

    public static <T extends ItemFrame> EntityVisualizerBuilder<T> itemFrame(EntityType<T> type) {
        return builder(type)
                .factory(ItemFrameVisual::new)
                .skipVanillaRender(ItemFrameVisual::shouldSkipVanilla);
    }

    private static <T extends Entity & ItemSupplier> EntityVisualizerBuilder<T> thrownItem(EntityType<T> type,
                                                                                           float scale,
                                                                                           boolean fullBright) {
        return builder(type)
                .factory((ctx, entity, partialTick) -> new ThrownItemVisual<>(ctx, entity, partialTick, scale,
                        fullBright))
                .skipVanillaRender(ThrownItemVisual::isSupported);
    }

    public static <T extends AbstractMinecart> EntityVisualizerBuilder<T> minecart(EntityType<T> type,
                                                                                   ModelLayerLocation variant) {
        return composable(type).apply(VanillaVisuals::commonElements)
                               .with(element(VisualElements.SHADOW).configure(
                                                                           new ShadowElement.Config(0.7f, ShadowElement.Config.DEFAULT_STRENGTH))
                                                                   .build())
                               .with(element(VisualElements.FIRE).build())
                               .with(element(VisualElements.MINECART).configure(variant)
                                                                     .build())
                               .apply(VanillaVisuals::experimentalElements)
                               .build();
    }

    public static <T extends Entity> void commonElements(EntityBuilder<T> builder) {
        builder.with(element(VisualElements.HITBOX).configure(false)
                                                   .build());
    }

    // Composable visuals fully replace vanilla rendering, so a named/leashed host would otherwise lose its
    // nameplate and leash: NAME_TAG restores it; LEASH is wired for a future leashable host.
    public static <T extends Entity> void experimentalElements(EntityBuilder<T> builder) {
        if (!EXPERIMENTAL) {
            return;
        }
        builder.with(element(VisualElements.NAME_TAG).build());
        builder.with(element(VisualElements.LEASH).build());
    }

    public static <T extends Entity> EntityBuilder<T> composable(EntityType<T> entityType) {
        return new EntityBuilder<>(entityType);
    }

    public static <T, C> ConfiguredElementImpl.ConfiguredElementBuilder<T, C> element(VisualElement<T, C> element) {
        return new ConfiguredElementImpl.ConfiguredElementBuilder<>(element);
    }

    public static <T extends BlockEntity> BlockEntityVisualizerBuilder<T> builder(BlockEntityType<T> type) {
        return new BlockEntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static <T extends Entity> EntityVisualizerBuilder<T> builder(EntityType<T> type) {
        return new EntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static class EntityBuilder<T extends Entity> {
        private final List<ConfiguredElement<? super T>> elements = new ArrayList<>();

        private final EntityType<T> entityType;
        @Nullable
        private VisualizationPredicate<T> predicate;

        public EntityBuilder(EntityType<T> entityType) {
            this.entityType = entityType;
        }

        /**
         * Set a predicate to control whether <em>all</em> elements are visualized.
         * <p>This is useful when you can't guarantee than an entity will support visualization for its entire lifetime.
         *
         * @param predicate A visualization predicate, returning {@code true} to indicate the entity should be visualized.
         */
        public EntityBuilder<T> shouldVisualize(VisualizationPredicate<T> predicate) {
            this.predicate = predicate;
            return this;
        }

        /**
         * Add a configured visual element to this visualizer.
         *
         * @param element The configured visual element.
         */
        public EntityBuilder<T> with(ConfiguredElement<? super T> element) {
            elements.add(element);
            return this;
        }

        public EntityBuilder<T> apply(Consumer<EntityBuilder<T>> mutate) {
            mutate.accept(this);
            return this;
        }

        public EntityVisualizerBuilder<T> build() {
            var elementsArray = elements.toArray(new ConfiguredElement[0]);

            if (predicate == null) {
                predicate = VisualizationPredicate.alwaysTrue();
            }

            var controller = new ComposableEntityVisual.Controller<T>(elementsArray, predicate);

            return builder(entityType).factory(
                    (ctx, entity, partialTick) -> new ComposableEntityVisual<T>(ctx, entity, partialTick, controller));
        }
    }
}
