package dev.engine_room.vanillin;

import dev.engine_room.flywheel.lib.compose.*;
import dev.engine_room.flywheel.lib.visual.ItemVisual;
import dev.engine_room.vanillin.config.BlockEntityVisualizerBuilder;
import dev.engine_room.vanillin.config.Configurator;
import dev.engine_room.vanillin.config.EntityVisualizerBuilder;
import dev.engine_room.vanillin.elements.ShadowElement;
import dev.engine_room.vanillin.visuals.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.*;
import net.minecraft.entity.monster.EntityZombie;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.visual.ArmorModels;
import dev.engine_room.flywheel.lib.visual.BipedEntityModel;
import dev.engine_room.flywheel.lib.visual.BipedLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EndermiteEntityModel;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.GuardianEntityModel;
import dev.engine_room.flywheel.lib.visual.HorseEntityModel;
import dev.engine_room.flywheel.lib.visual.IllagerEntityModel;
import dev.engine_room.flywheel.lib.visual.PolarBearEntityModel;
import dev.engine_room.flywheel.lib.visual.QuadrupedEntityModel;
import dev.engine_room.flywheel.lib.visual.SilverfishEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.SpiderEntityModel;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelCow;
import net.minecraft.client.model.ModelEnderman;
import net.minecraft.client.model.ModelPolarBear;
import net.minecraft.client.model.ModelSheep2;
import net.minecraft.client.model.ModelSkeleton;
import net.minecraft.client.model.ModelZombie;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCaveSpider;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityElderGuardian;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityEndermite;
import net.minecraft.entity.monster.EntityEvoker;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityGiantZombie;
import net.minecraft.entity.monster.EntityGuardian;
import net.minecraft.entity.monster.EntityHusk;
import net.minecraft.entity.monster.EntityIllusionIllager;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntityPolarBear;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.entity.monster.EntitySpellcasterIllager;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityStray;
import net.minecraft.entity.monster.EntityVex;
import net.minecraft.entity.monster.EntityVindicator;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.monster.EntityWitherSkeleton;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityDonkey;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.entity.passive.EntityMule;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityParrot;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityRabbit;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.passive.EntitySkeletonHorse;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.passive.EntityZombieHorse;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class VanillaVisuals {
    public static final Configurator CONFIGURATOR = new Configurator();

    // Stable visuals are enabled by default always.
    public static final boolean STABLE = true;
    // Mirrors upstream: dev-only by default; users opt in per entity via config/flywheel-vanilla.json.
    public static final boolean EXPERIMENTAL = FMLLaunchHandler.isDeobfuscatedEnvironment();

    private VanillaVisuals() {
    }

    public static void init() {
        blockEntity(TileEntityChest.class).factory(ChestVisual::new).apply(STABLE);
        blockEntity(TileEntityEnderChest.class).factory(EnderChestVisual::new).apply(STABLE);
        blockEntity(TileEntityShulkerBox.class).factory(ShulkerBoxVisual::new).apply(STABLE);

        minecart(EntityMinecartEmpty.class).apply(STABLE);
        minecart(EntityMinecartChest.class).apply(STABLE);
        minecart(EntityMinecartFurnace.class).apply(STABLE);
        minecart(EntityMinecartHopper.class).apply(STABLE);
        minecart(EntityMinecartCommandBlock.class).apply(STABLE);

        composable(EntityMinecartTNT.class).apply(VanillaVisuals::commonElements)
                .with(element(VisualElements.SHADOW).configure(new ShadowElement.Config(0.7F, ShadowElement.Config.DEFAULT_STRENGTH)).build())
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.TNT_MINECART).build())
                .build()
                .skipVanillaRender(MinecartVisual::shouldSkipRender)
                .apply(STABLE);

        itemFrame(EntityItemFrame.class).apply(STABLE);

        composable(EntityItem.class)
                .with(element(VisualElements.ITEM_ENTITY).build())
                .shouldVisualize((ctx, entity) -> ItemVisual.isSupported(entity))
                .build()
                .skipVanillaRender(ItemVisual::isSupported)
                .apply(STABLE);

        bipedLivingEntity(EntityZombie.class, new BipedEntityModel<>(ModelZombie::new), "textures/entity/zombie/zombie.png", "zombie", 0.5F, 1.0F, 90.0F);
        bipedLivingEntity(EntitySkeleton.class, new BipedEntityModel<>(ModelSkeleton::new), "textures/entity/skeleton/skeleton.png", "skeleton", 0.5F, 1.0F, 90.0F);
        entity(EntityStray.class).factory(StrayVisual::new)
                .skipVanillaRender(e -> !e.isChild() && !e.isInvisible() && !ArmorModels.hasCustomArmorModel(e))
                .apply(EXPERIMENTAL);
        bipedLivingEntity(EntityWitherSkeleton.class, new BipedEntityModel<>(ModelSkeleton::new), "textures/entity/skeleton/wither_skeleton.png", "wither_skeleton", 0.5F, 1.2F, 90.0F);
        bipedLivingEntity(EntityHusk.class, new BipedEntityModel<>(ModelZombie::new), "textures/entity/zombie/husk.png", "husk", 0.5F, 1.0625F, 90.0F);
        bipedLivingEntity(EntityPigZombie.class, new BipedEntityModel<>(ModelZombie::new), "textures/entity/zombie_pigman.png", "zombie_pigman", 0.5F, 1.0F, 90.0F);
        // Giant: ModelZombie at 6x (distinct cacheKey from zombie so the 6x-baked bone cache stays separate).
        bipedLivingEntity(EntityGiantZombie.class, new BipedEntityModel<>(ModelZombie::new), "textures/entity/zombie/zombie.png", "giant", 3.0F, 6.0F, 90.0F);

        entity(EntityPig.class).factory(PigVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        livingEntityWithBabies(EntityCow.class, new QuadrupedEntityModel<>(ModelCow::new), "textures/entity/cow/cow.png", "cow", 0.7F, 1.0F, 90.0F);

        Material sheepBody = livingMaterial("textures/entity/sheep/sheep.png");
        Material sheepWool = livingMaterial("textures/entity/sheep/sheep_fur.png");
        entity(EntitySheep.class)
                .factory((c, e, p) -> new SheepVisual(c, e, p, new QuadrupedEntityModel<>(ModelSheep2::new), sheepBody, sheepWool, "sheep", 0.7F))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        // Eyes texture shared by spider + cave spider (LayerSpiderEyes hardcodes it for both).
        Material spiderEyes = emissiveMaterial("textures/entity/spider_eyes.png");
        Material spiderBody = livingMaterial("textures/entity/spider/spider.png");
        entity(EntitySpider.class)
                .factory((c, e, p) -> new SpiderVisual(c, e, p, new SpiderEntityModel(), spiderBody, spiderEyes, "spider", 1.0F, 1.0F))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        Material caveBody = livingMaterial("textures/entity/spider/cave_spider.png");
        entity(EntityCaveSpider.class)
                .factory((c, e, p) -> new SpiderVisual(c, e, p, new SpiderEntityModel(), caveBody, spiderEyes, "cave_spider", 0.7F, 0.7F))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        entity(EntityChicken.class).factory(ChickenVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityCreeper.class).factory(CreeperVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        entity(EntityMagmaCube.class).factory(MagmaCubeVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        livingEntity(EntitySilverfish.class, new SilverfishEntityModel(), "textures/entity/silverfish.png", "silverfish", 0.3F, 1.0F, 180.0F);
        livingEntity(EntityEndermite.class, new EndermiteEntityModel(), "textures/entity/endermite.png", "endermite", 0.3F, 1.0F, 180.0F);
        livingEntityWithBabies(EntityPolarBear.class, new PolarBearEntityModel(), "textures/entity/bear/polarbear.png", "polar_bear", 0.7F, 1.2F, 90.0F);
        entity(EntitySnowman.class).factory(SnowGolemVisual::new)
                .skipVanillaRender(e -> !e.isChild() && !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityIronGolem.class).factory(IronGolemVisual::new)
                .skipVanillaRender(e -> !e.isChild() && !e.isInvisible())
                .apply(EXPERIMENTAL);
        // ModelEnderman has no no-arg ctor.
        Material endermanBody = livingMaterial("textures/entity/enderman/enderman.png");
        Material endermanEyes = emissiveMaterial("textures/entity/enderman/enderman_eyes.png");
        entity(EntityEnderman.class)
                .factory((c, e, p) -> new EndermanVisual(c, e, p, new BipedEntityModel<>(() -> new ModelEnderman(0.0F)), endermanBody, endermanEyes, "enderman", 0.5F))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        // Registry is exact-class keyed, so guardian + elder guardian (a subclass) never collide.
        livingEntity(EntityGuardian.class, new GuardianEntityModel(), "textures/entity/guardian.png", "guardian", 0.5F, 1.0F, 90.0F);
        livingEntity(EntityElderGuardian.class, new GuardianEntityModel(), "textures/entity/guardian_elder.png", "elder_guardian", 0.5F, 2.35F, 90.0F);

        entity(EntityBlaze.class).factory(BlazeVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityVex.class).factory(VexVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityBat.class).factory(BatVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityWitch.class).factory(WitchVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntitySquid.class).factory(SquidVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        entity(EntitySlime.class).factory(SlimeVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        entity(EntityMooshroom.class).factory(MooshroomVisual::new)
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        entity(EntityVillager.class).factory(VillagerVisual::new)
                .skipVanillaRender(VillagerAtlas::isInstanceable)
                .apply(EXPERIMENTAL);
        entity(EntityZombieVillager.class).factory(ZombieVillagerVisual::new)
                .skipVanillaRender(ZombieVillagerAtlas::isInstanceable)
                .apply(EXPERIMENTAL);

        entity(EntityParrot.class).factory(ParrotVisual::new).skipVanillaRender(ParrotVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityOcelot.class).factory(OcelotVisual::new).skipVanillaRender(OcelotVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityRabbit.class).factory(RabbitVisual::new).skipVanillaRender(RabbitVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityGhast.class).factory(GhastVisual::new).skipVanillaRender(GhastVisual::isInstanceable).apply(EXPERIMENTAL);

        // The per-mob predicate gates a primary-hand held item; evoker is empty-handed in vanilla so its
        // gate is a no-op there (only matters for modded items).
        Material evokerMaterial = livingMaterial("textures/entity/illager/evoker.png");
        entity(EntityEvoker.class)
                .factory((c, e, p) -> new IllagerVisual(c, e, p, new IllagerEntityModel(), evokerMaterial, "evoker", 0.5F,
                        le -> ((EntitySpellcasterIllager) le).isSpellcasting()))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
        Material vindicatorMaterial = livingMaterial("textures/entity/illager/vindicator.png");
        entity(EntityVindicator.class)
                .factory((c, e, p) -> new IllagerVisual(c, e, p, new IllagerEntityModel(), vindicatorMaterial, "vindicator", 0.5F,
                        le -> ((EntityVindicator) le).isAggressive()))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);

        entity(EntityWolf.class).factory(WolfVisual::new).skipVanillaRender(WolfVisual::isInstanceable).apply(EXPERIMENTAL);

        // HorseFamilyVisual's int arg is the chest-toggle root bone index: -1 = no chest (skeleton/zombie
        // horse), 21 = donkey/mule. EntityHorse uses its own markings-atlas visual below.
        entity(EntitySkeletonHorse.class)
                .factory((c, e, p) -> { HorseEntityModel m = new HorseEntityModel(false); return new HorseFamilyVisual(c, e, p, m, livingMaterial("textures/entity/horse/horse_skeleton.png"), "skeleton_horse", 1.0F, -1, m.tackStart()); })
                .skipVanillaRender(HorseFamilyVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityZombieHorse.class)
                .factory((c, e, p) -> { HorseEntityModel m = new HorseEntityModel(false); return new HorseFamilyVisual(c, e, p, m, livingMaterial("textures/entity/horse/horse_zombie.png"), "zombie_horse", 1.0F, -1, m.tackStart()); })
                .skipVanillaRender(HorseFamilyVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityDonkey.class)
                .factory((c, e, p) -> { HorseEntityModel m = new HorseEntityModel(true); return new HorseFamilyVisual(c, e, p, m, livingMaterial("textures/entity/horse/donkey.png"), "donkey", 0.87F, 21, m.tackStart()); })
                .skipVanillaRender(HorseFamilyVisual::isInstanceable).apply(EXPERIMENTAL);
        entity(EntityMule.class)
                .factory((c, e, p) -> { HorseEntityModel m = new HorseEntityModel(true); return new HorseFamilyVisual(c, e, p, m, livingMaterial("textures/entity/horse/mule.png"), "mule", 0.92F, 21, m.tackStart()); })
                .skipVanillaRender(HorseFamilyVisual::isInstanceable).apply(EXPERIMENTAL);

        entity(EntityHorse.class).factory(HorseVisual::new).skipVanillaRender(HorseVisual::isInstanceable).apply(EXPERIMENTAL);

        entity(EntityLlama.class).factory(LlamaVisual::new).skipVanillaRender(LlamaVisual::isInstanceable).apply(EXPERIMENTAL);

        entity(EntityShulker.class).factory(ShulkerVisual::new).skipVanillaRender(ShulkerVisual::isInstanceable).apply(EXPERIMENTAL);

        entity(EntityWither.class).factory(WitherVisual::new).skipVanillaRender(WitherVisual::isInstanceable).apply(EXPERIMENTAL);

        // Always instanced: the illusioner renders even while invisible (the mirror illusion).
        entity(EntityIllusionIllager.class).factory(IllusionerVisual::new).skipVanillaRender(e -> true).apply(EXPERIMENTAL);

        entity(EntityDragon.class).factory(EnderDragonVisual::new)
                .skipVanillaRender(e -> e.deathTicks <= 0 && e.deathTime <= 0 && !e.isInvisible()).apply(EXPERIMENTAL);
    }

    // 1.12.2: atlas builds upload through Minecraft.getTextureManager(), which Forge does not create until
    // just after beginMinecraftLoading — so these MUST register at init, not preInit (registerReloadListener
    // fires the build synchronously to bootstrap, and at preInit the TextureManager is still null). See
    // VariantAtlasHolder. The skipVanillaRender method-refs above are lazy, so the factories register at
    // preInit while their atlases come up here.
    public static void registerAtlases() {
        VillagerAtlas.register();
        ZombieVillagerAtlas.register();
        ParrotVisual.register();
        OcelotVisual.register();
        RabbitVisual.register();
        GhastVisual.register();
        WolfVisual.register();
        HorseVisual.register();
        LlamaVisual.register();
        ShulkerVisual.register();
    }

    // Upstream's overloaded `builder(BlockEntityType)`/`builder(EntityType)` collapse to the
    // same erased Class<T> signature in 1.12.2, so split into distinct method names.
    public static <T extends TileEntity> BlockEntityVisualizerBuilder<T> blockEntity(Class<T> type) {
        return new BlockEntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static <T extends Entity> EntityVisualizerBuilder<T> entity(Class<T> type) {
        return new EntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static <T extends EntityLivingBase, M extends ModelBase> void livingEntity(
            Class<T> type, EntityModel<M> model, String texture, String cacheKey,
            float shadowRadius, float uniformScale, float deathMaxRotation) {
        Material material = livingMaterial(texture);
        entity(type)
                .factory((ctx, entity, partialTick) -> new SimpleLivingEntityVisual<>(
                        ctx, entity, partialTick, model, material, cacheKey, shadowRadius, uniformScale, deathMaxRotation))
                .skipVanillaRender(e -> !e.isChild() && !e.isInvisible())
                .apply(EXPERIMENTAL);
    }

    // Instances the baby form too; requires the model's hasBabyTransform. Pose-mirroring layers are
    // baby-safe (they copy composed matrices), but layers consuming rootPose directly are not — see
    // LivingLayer.
    public static <T extends EntityLivingBase, M extends ModelBase> void livingEntityWithBabies(
            Class<T> type, EntityModel<M> model, String texture, String cacheKey,
            float shadowRadius, float uniformScale, float deathMaxRotation) {
        Material material = livingMaterial(texture);
        entity(type)
                .factory((ctx, entity, partialTick) -> new SimpleLivingEntityVisual<>(
                        ctx, entity, partialTick, model, material, cacheKey, shadowRadius, uniformScale, deathMaxRotation, true))
                .skipVanillaRender(e -> !e.isInvisible())
                .apply(EXPERIMENTAL);
    }

    public static <T extends EntityLivingBase, M extends ModelBiped> void bipedLivingEntity(
            Class<T> type, EntityModel<M> model, String texture, String cacheKey,
            float shadowRadius, float uniformScale, float deathMaxRotation) {
        Material material = livingMaterial(texture);
        entity(type)
                .factory((ctx, entity, partialTick) -> new BipedLivingEntityVisual<>(
                        ctx, entity, partialTick, model, material, cacheKey, shadowRadius, uniformScale, deathMaxRotation))
                .skipVanillaRender(e -> !e.isInvisible() && !ArmorModels.hasCustomArmorModel(e))
                .apply(EXPERIMENTAL);
    }

    public static Material livingMaterial(String texture) {
        return dev.engine_room.vanillin.visuals.EntityMaterials.living(texture);
    }

    public static Material emissiveMaterial(String texture) {
        return dev.engine_room.vanillin.visuals.EntityMaterials.emissive(texture);
    }

    public static <T extends Entity> EntityBuilder<T> composable(Class<T> entityType) {
        return new EntityBuilder<>(entityType);
    }

    public static <T, C> ConfiguredElementImpl.ConfiguredElementBuilder<T, C> element(VisualElement<T, C> element) {
        return new ConfiguredElementImpl.ConfiguredElementBuilder<>(element);
    }

    public static <T extends Entity> void commonElements(EntityBuilder<T> builder) {
        builder.with(element(VisualElements.HITBOX).configure(false).build());
    }

    public static <T extends EntityItemFrame> EntityVisualizerBuilder<T> itemFrame(Class<T> type) {
        return composable(type).apply(VanillaVisuals::commonElements)
                // CrankShaft divergence: render fire on briefly-burning item frames; upstream omits this.
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.ITEM_FRAME).build())
                .shouldVisualize((ctx, entity) -> ItemFrameVisual.shouldVisualize(entity))
                .build()
                .skipVanillaRender(ItemFrameVisual::shouldVisualize);
    }

    public static <T extends EntityMinecart> EntityVisualizerBuilder<T> minecart(Class<T> type) {
        return composable(type).apply(VanillaVisuals::commonElements)
                .with(element(VisualElements.SHADOW).configure(new ShadowElement.Config(0.7F, ShadowElement.Config.DEFAULT_STRENGTH)).build())
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.MINECART).build())
                .build()
                .skipVanillaRender(MinecartVisual::shouldSkipRender);
    }

    public static final class EntityBuilder<T extends Entity> {
        private final List<ConfiguredElement<? super T>> elements = new ArrayList<>();
        private final Class<T> entityType;
        @Nullable
        private VisualizationPredicate<T> predicate;

        public EntityBuilder(Class<T> entityType) {
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

        @SuppressWarnings("unchecked")
        public EntityVisualizerBuilder<T> build() {
            ConfiguredElement<? super T>[] elementsArray = elements.toArray(new ConfiguredElement[0]);
            VisualizationPredicate<T> p = predicate != null ? predicate : VisualizationPredicate.alwaysTrue();
            ComposableEntityVisual.Controller<T> controller = new ComposableEntityVisual.Controller<>(elementsArray, p);
            return entity(entityType).factory((ctx, entity, partialTick) -> new ComposableEntityVisual<>(ctx, entity, partialTick, controller));
        }
    }
}
