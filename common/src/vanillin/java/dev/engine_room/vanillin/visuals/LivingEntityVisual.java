package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import dev.engine_room.flywheel.lib.visual.component.NameTagComponent;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import dev.engine_room.vanillin.item.ItemModels;
import dev.engine_room.vanillin.item.SpecialItemModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.effects.SpearAnimations;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Instanced equivalent of vanilla {@code LivingEntityRenderer}: the baked {@link ModelPart} tree as one
 * {@link InstanceTree}, reposed off a render-thread snapshot; protected per-mob hooks come via {@link Config}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class LivingEntityVisual<T extends LivingEntity> extends EntityModelVisual<T, LivingEntityRenderState> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LivingEntityVisual.class);
    private static final Set<EntityType<?>> MISMATCH_WARNED = ConcurrentHashMap.newKeySet();
    // Body-equipment base material, polygon-offset so coplanar equipment (strider saddle) wins the depth test.
    private static final Map<Identifier, Material> EQUIPMENT_MATERIALS = new ConcurrentHashMap<>();
    // WingsLayer pushes the elytra 0.125 behind the body before rendering.
    private static final Matrix4fc WINGS_OFFSET = new Matrix4f().translate(0.0F, 0.0F, 0.125F);
    private static final Map<Identifier, Material> TRANSLUCENT_BODY_MATERIALS = new ConcurrentHashMap<>();
    private static final Map<DynamicKey, Material> DYNAMIC_OVERLAY_MATERIALS = new ConcurrentHashMap<>();
    private final Config config;
    private final PoseStack handPose = new PoseStack();
    private final boolean heldItemsActive;
    @Nullable
    private final HandSlot rightSlot;
    @Nullable
    private final HandSlot leftSlot;
    private final boolean customHeldActive;
    @Nullable
    private final HandSlot[] customSlots;
    private final boolean headItemActive;
    @Nullable
    private final HandSlot headSlot;
    private final boolean armorActive;
    @Nullable
    private final InstancedArmorLayer armor;
    private final boolean bodyEquipmentActive;
    @Nullable
    private final InstancedEquipmentLayer[] bodyEquip;
    private final EntityModel<LivingEntityRenderState> @Nullable [] equipSelfModels;
    private final ModelPart @Nullable [] @Nullable [] equipSelfParts;
    private final boolean overlaysActive;
    @Nullable
    private final Map<String, Integer> boneIndex;
    private final boolean blockDecorationsActive;
    private final boolean dynamicBlocksActive;
    @Nullable
    private InstancedSpecialItem wornHead;
    @Nullable
    private InstancedOverlayLayer overlayLayer;
    @Nullable
    private InstancedBlockDecorations blockDecos;
    @Nullable
    private InstancedDynamicBlocks blockDynamics;
    @Nullable
    private FireComponent fire;
    @Nullable
    private ShadowComponent shadow;
    @Nullable
    private NameTagComponent nameTag;

    public LivingEntityVisual(VisualizationContext ctx, T entity, float partialTick, Config config) {
        super(ctx, entity, partialTick, modelLayers(config), modelFactories(config),
                renderer -> (EntityModel) ((LivingEntityRenderer) renderer).getModel());
        this.config = config;

        // ItemInHandLayer exists only on ArmedModel/ArmedEntityRenderState renderers; gate on both so the capture cast is safe.
        this.heldItemsActive = config.heldItems && model(
                0) instanceof ArmedModel && state instanceof ArmedEntityRenderState;
        this.rightSlot = heldItemsActive ? new HandSlot() : null;
        this.leftSlot = heldItemsActive ? new HandSlot() : null;

        this.customHeldActive = !config.customHeldItems.isEmpty();
        if (customHeldActive) {
            this.customSlots = new HandSlot[config.customHeldItems.size()];
            for (int i = 0; i < customSlots.length; i++) {
                customSlots[i] = new HandSlot();
            }
        } else {
            this.customSlots = null;
        }

        this.headItemActive = config.headTransforms != null && model(0) instanceof HeadedModel;
        this.headSlot = headItemActive ? new HandSlot() : null;

        this.armorActive = config.armorSet != null && state instanceof HumanoidRenderState;
        this.overlaysActive = !config.overlays.isEmpty();
        this.blockDecorationsActive = !config.blockDecorations.isEmpty();
        this.dynamicBlocksActive = !config.dynamicBlocks.isEmpty();
        this.bodyEquipmentActive = !config.bodyEquipment.isEmpty();
        if (config.modelVariants != null && (heldItemsActive || customHeldActive || armorActive || overlaysActive
                || blockDecorationsActive || dynamicBlocksActive || bodyEquipmentActive)) {
            // The decoration layers pose off a single bone-name map; per-variant bone maps are not wired.
            throw new IllegalArgumentException("model variants do not compose with decoration layers");
        }
        float[] bodyRest = null;
        if (armorActive || overlaysActive || blockDecorationsActive || dynamicBlocksActive || bodyEquipmentActive) {
            Map<String, Integer> index = new HashMap<>();
            String[] names = partNames(0);
            for (int i = 0; i < names.length; i++) {
                index.putIfAbsent(names[i], i);
            }
            this.boneIndex = index;
            // Body rest poses: equipment poses as rest_layer + (captured - rest_body), since vanilla's layer
            // setupAnim writes angles absolutely but leaves pivots at the layer bake's rest.
            bodyRest = restPoses(parts(0));
        } else {
            this.boneIndex = null;
        }
        EquipmentAssetManager assets = (armorActive || bodyEquipmentActive)
                ? Minecraft.getInstance().getEntityRenderDispatcher().equipmentAssets : null;
        this.armor = armorActive ? new InstancedArmorLayer(instancerProvider(), assets, config.armorSet,
                config.armorBaby, boneIndex, bodyRest) : null;
        if (bodyEquipmentActive) {
            this.bodyEquip = new InstancedEquipmentLayer[config.bodyEquipment.size()];
            EntityModel[] selfModels = null;
            ModelPart[][] selfParts = null;
            for (int i = 0; i < bodyEquip.length; i++) {
                BodyEquipment be = config.bodyEquipment.get(i);
                bodyEquip[i] = new InstancedEquipmentLayer(instancerProvider(), assets, be, boneIndex, bodyRest);
                if (be.selfAnimated() != null) {
                    if (selfModels == null) {
                        selfModels = new EntityModel[bodyEquip.length];
                        selfParts = new ModelPart[bodyEquip.length][];
                    }
                    selfModels[i] = EntityModelVisual.sharedModel(be.modelLayer(), be.selfAnimated());
                    List<ModelPart> parts = new ArrayList<>();
                    EntityModelVisual.flattenModel(selfModels[i].root(), "", -1, parts, new ArrayList<>(),
                            new ArrayList<>());
                    selfParts[i] = parts.toArray(new ModelPart[0]);
                }
            }
            this.equipSelfModels = selfModels;
            this.equipSelfParts = selfParts;
        } else {
            this.bodyEquip = null;
            this.equipSelfModels = null;
            this.equipSelfParts = null;
        }

        createComponents();
    }

    static Material materialFor(Identifier texture) {
        return EntityModelVisual.materialFor(texture);
    }

    static Material equipmentMaterial(Identifier texture) {
        return EQUIPMENT_MATERIALS.computeIfAbsent(texture, tex -> SimpleMaterial.builderOf(materialFor(tex))
                                                                                 .polygonOffset(true)
                                                                                 .build());
    }

    static Material translucentBodyMaterial(Identifier texture) {
        return TRANSLUCENT_BODY_MATERIALS.computeIfAbsent(texture, tex -> SimpleMaterial.builder()
                                                                                        .transparency(
                                                                                                Transparency.ORDER_INDEPENDENT)
                                                                                        .backfaceCulling(false)
                                                                                        .mipmap(false)
                                                                                        .texture(tex)
                                                                                        .build());
    }

    static Material overlayMaterial(Identifier texture, OverlayKind kind) {
        SimpleMaterial.Builder builder = SimpleMaterial.builder()
                                                       .backfaceCulling(false)
                                                       .mipmap(false)
                                                       .texture(texture);
        switch (kind) {
            case CUTOUT -> builder.cutout(CutoutShaders.ONE_TENTH);
            case COPLANAR -> builder.cutout(CutoutShaders.ONE_TENTH)
                                    .polygonOffset(true);
            case TRANSLUCENT -> builder.transparency(Transparency.ORDER_INDEPENDENT);
            // OFF cardinal lighting = flat, no directional dimming (the eyes are already fullbright).
            case EMISSIVE -> builder.transparency(Transparency.ADDITIVE)
                                    .writeMask(WriteMask.COLOR)
                                    .cardinalLightingMode(CardinalLightingMode.OFF);
            case EMISSIVE_TRANSLUCENT -> builder.transparency(Transparency.ORDER_INDEPENDENT)
                                                .writeMask(WriteMask.COLOR)
                                                .cardinalLightingMode(CardinalLightingMode.OFF);
        }
        return builder.build();
    }

    static Material dynamicOverlayMaterial(Identifier texture, OverlayKind kind) {
        return DYNAMIC_OVERLAY_MATERIALS.computeIfAbsent(new DynamicKey(texture, kind),
                k -> overlayMaterial(k.texture(), k.kind()));
    }

    private static ModelLayerLocation[] modelLayers(Config config) {
        if (config.modelVariants == null) {
            return new ModelLayerLocation[]{config.layer};
        }
        ModelLayerLocation[] layers = new ModelLayerLocation[config.modelVariants.size()];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = config.modelVariants.get(i).layer();
        }
        return layers;
    }

    private static Function<ModelPart, ? extends EntityModel<LivingEntityRenderState>>[] modelFactories(Config config) {
        Function<ModelPart, ? extends EntityModel<LivingEntityRenderState>>[] factories = new Function[
                config.modelVariants == null ? 1 : config.modelVariants.size()];
        if (config.modelVariants == null) {
            factories[0] = (Function) config.modelFactory;
        } else {
            for (int i = 0; i < factories.length; i++) {
                factories[i] = (Function) config.modelVariants.get(i).factory();
            }
        }
        return factories;
    }

    static float[] posedFloats(ModelPart[] parts) {
        float[] out = new float[parts.length * 9];
        for (int i = 0; i < parts.length; i++) {
            ModelPart p = parts[i];
            int b = i * 9;
            out[b] = p.x;
            out[b + 1] = p.y;
            out[b + 2] = p.z;
            out[b + 3] = p.xRot;
            out[b + 4] = p.yRot;
            out[b + 5] = p.zRot;
            out[b + 6] = p.xScale;
            out[b + 7] = p.yScale;
            out[b + 8] = p.zScale;
        }
        return out;
    }

    static float[] layerRestPoses(ModelLayerLocation layer) {
        return EntityModelVisual.layerRestPoses(layer);
    }

    static int[] resolveBonePath(@Nullable String path, Map<String, Integer> boneIndex) {
        if (path == null) {
            return new int[0];
        }
        String[] segments = path.split("/", -1);
        int[] chain = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            Integer index = boneIndex.get(segments[i]);
            if (index == null) {
                return new int[0];
            }
            chain[i] = index;
        }
        return chain;
    }

    private static void hideSlot(@Nullable HandSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.instance != null && slot.visible) {
            slot.instance.setVisible(false);
            slot.visible = false;
        }
        hideSpecial(slot);
    }

    private static void hideSpecial(HandSlot slot) {
        if (slot.special != null) {
            slot.special.hide();
        }
    }

    private static void deleteSlot(@Nullable HandSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.instance != null) {
            slot.instance.delete();
            slot.instance = null;
        }
        if (slot.special != null) {
            slot.special.delete();
            slot.special = null;
        }
    }

    // Port: inline of protected setupRotations (can't AT-widen without breaking subclass overrides); public so per-mob rotation overrides can reuse it as their base.
    public static void baseRotations(PoseStack pose, LivingEntityRenderState s, float bodyRot, float entityScale,
                                     float flipDegrees) {
        baseRotations(pose, s, bodyRot, entityScale, flipDegrees, s.isFullyFrozen);
    }

    public static void baseRotations(PoseStack pose, LivingEntityRenderState s, float bodyRot, float entityScale,
                                     float flipDegrees, boolean shaking) {
        if (shaking) {
            bodyRot += (float) (Math.cos(Mth.floor(s.ageInTicks) * 3.25F) * Math.PI * 0.4F);
        }
        if (!s.hasPose(Pose.SLEEPING)) {
            pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyRot));
        }
        if (s.deathTime > 0.0F) {
            float fall = (s.deathTime - 1.0F) / 20.0F * 1.6F;
            fall = Mth.sqrt(fall);
            if (fall > 1.0F) {
                fall = 1.0F;
            }
            pose.mulPose(Axis.ZP.rotationDegrees(fall * flipDegrees));
        } else if (s.isAutoSpinAttack) {
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F - s.xRot));
            pose.mulPose(Axis.YP.rotationDegrees(s.ageInTicks * -75.0F));
        } else if (s.hasPose(Pose.SLEEPING)) {
            Direction bed = s.bedOrientation;
            float angle = bed != null ? sleepDirectionToRotation(bed) : bodyRot;
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            pose.mulPose(Axis.ZP.rotationDegrees(flipDegrees));
            pose.mulPose(Axis.YP.rotationDegrees(270.0F));
        } else if (s.isUpsideDown) {
            pose.translate(0.0F, (s.boundingBoxHeight + 0.1F) / entityScale, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        }
    }

    private static float sleepDirectionToRotation(Direction direction) {
        return switch (direction) {
            case SOUTH -> 90.0F;
            case WEST -> 0.0F;
            case NORTH -> 270.0F;
            case EAST -> 180.0F;
            default -> 0.0F;
        };
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (config.vanillaHandles(entity)) {
            hideBody();
            deleteComponents();
            return;
        }
        if (fire == null) {
            createComponents();
        }

        double shadowDistSq = entity.distanceToSqr(ctx.camera().position());
        shadow.radius(config.shadowRadius != null ? config.shadowRadius.radius(entity)
                : renderer.shadowRadius * entity.getScale() * entity.getAgeScale());
        shadow.strength((float) (1.0 - shadowDistSq / 256.0));

        fire.beginFrame(ctx);
        shadow.beginFrame(ctx);
        nameTag.beginFrame(ctx);

        super.beginFrame(ctx);
    }

    @Override
    protected void setupRootPose(PoseStack pose, LivingEntityRenderState state) {
        if (state.hasPose(Pose.SLEEPING) && state.bedOrientation != null) {
            float headOffset = state.eyeHeight - 0.1F;
            pose.translate(-state.bedOrientation.getStepX() * headOffset, 0.0F,
                    -state.bedOrientation.getStepZ() * headOffset);
        }
        float scale = state.scale;
        pose.scale(scale, scale, scale);
        if (config.rotations != null) {
            config.rotations.apply(pose, state, state.bodyRot, scale);
        } else {
            boolean shaking = config.shaking != null ? config.shaking.shaking(state) : state.isFullyFrozen;
            baseRotations(pose, state, state.bodyRot, scale, config.flipDegrees, shaking);
        }
        pose.scale(-1.0F, -1.0F, 1.0F);
        if (config.scale != null) {
            config.scale.apply(state, pose);
        }
        pose.translate(0.0F, -1.501F, 0.0F);
    }

    @Override
    protected int modelVariant(LivingEntityRenderState state) {
        return config.variantSelector == null ? 0 : config.variantSelector.applyAsInt(state);
    }

    @Override
    protected Material material(Identifier texture) {
        return config.translucentBody ? translucentBodyMaterial(texture) : materialFor(texture);
    }

    @Override
    protected Identifier texture(LivingEntityRenderState state) {
        Identifier texture = config.bodyTexture == null ? null : config.bodyTexture.apply(state);
        if (texture == null) {
            texture = ((LivingEntityRenderer) renderer).getTextureLocation(state);
        }
        return texture;
    }

    @Override
    protected int overlay(LivingEntityRenderState state) {
        float whiteProgress = config.whiteOverlay == null ? 0.0F : config.whiteOverlay.apply(state);
        return LivingEntityRenderer.getOverlayCoords(state, whiteProgress);
    }

    @Override
    protected int bodyColor(LivingEntityRenderState state) {
        return config.bodyColor == null ? -1 : config.bodyColor.applyAsInt(state);
    }

    @Override
    @Nullable
    protected Object captureExtra(LivingEntityRenderState state, EntityModel<LivingEntityRenderState> model,
                                  Matrix4fc local) {
        // Held items ride the same render-thread capture: the worker only needs root x handLocal.
        HandItems hands = null;
        if (heldItemsActive) {
            ArmedEntityRenderState as = (ArmedEntityRenderState) state;
            hands = new HandItems(handMatrix(HumanoidArm.RIGHT, as), as.rightHandItemStack,
                    handMatrix(HumanoidArm.LEFT, as), as.leftHandItemStack);
        }

        Equipment equipment = null;
        if (armorActive) {
            HumanoidRenderState hs = (HumanoidRenderState) state;
            equipment = new Equipment(hs.headEquipment, hs.chestEquipment, hs.legsEquipment, hs.feetEquipment);
        }

        BlockState[] dynBlocks = null;
        if (dynamicBlocksActive) {
            dynBlocks = new BlockState[config.dynamicBlocks.size()];
            for (int i = 0; i < dynBlocks.length; i++) {
                dynBlocks[i] = config.dynamicBlocks.get(i).state().apply(entity);
            }
        }
        boolean heldShown = config.heldItemsVisible == null || config.heldItemsVisible.test(state);

        int[] overlayColors = null;
        Identifier[] overlayTextures = null;
        if (overlaysActive) {
            overlayColors = new int[config.overlays.size()];
            overlayTextures = new Identifier[config.overlays.size()];
            for (int i = 0; i < config.overlays.size(); i++) {
                Overlay o = config.overlays.get(i);
                overlayColors[i] = o.color() == null ? -1 : o.color().applyAsInt(state);
                overlayTextures[i] = o.textureResolver() == null ? null : o.textureResolver().apply(state);
            }
        }

        CustomHeldCapture[] customHeld = null;
        if (customHeldActive) {
            customHeld = new CustomHeldCapture[config.customHeldItems.size()];
            for (int i = 0; i < customHeld.length; i++) {
                CustomHeldItem chi = config.customHeldItems.get(i);
                ItemStack stack = chi.stack().apply(entity);
                boolean vis = chi.visible() == null || chi.visible().test(state);
                Matrix4f m = vis && !stack.isEmpty() ? chi.pose().pose(model, state) : null;
                customHeld[i] = new CustomHeldCapture(m, stack);
            }
        }

        ItemStack[] bodyEquipItems = null;
        boolean[] bodyEquipRidden = null;
        boolean[] bodyEquipFallback = null;
        float[][] bodyEquipSelfPose = null;
        if (bodyEquipmentActive) {
            bodyEquipItems = new ItemStack[config.bodyEquipment.size()];
            bodyEquipRidden = new boolean[config.bodyEquipment.size()];
            bodyEquipFallback = new boolean[config.bodyEquipment.size()];
            for (int i = 0; i < bodyEquipItems.length; i++) {
                BodyEquipment be = config.bodyEquipment.get(i);
                bodyEquipItems[i] = be.item().apply(entity);
                bodyEquipRidden[i] = be.ridden() == null || be.ridden().test(state);
                bodyEquipFallback[i] = be.fallbackAsset() != null && (be.fallbackWhen() == null || be.fallbackWhen()
                                                                                                     .test(state));
                if (equipSelfModels != null && equipSelfModels[i] != null) {
                    if (bodyEquipSelfPose == null) {
                        bodyEquipSelfPose = new float[bodyEquipItems.length][];
                    }
                    equipSelfModels[i].setupAnim(state);
                    bodyEquipSelfPose[i] = posedFloats(equipSelfParts[i]);
                }
            }
        }

        return new LivingExtra(hands, equipment, evaluateConditions(state), heldShown, dynBlocks, overlayColors,
                overlayTextures, customHeld, bodyEquipItems, bodyEquipRidden, bodyEquipFallback, bodyEquipSelfPose,
                headItemActive ? captureHead(model) : null);
    }

    @Nullable
    private HeadCapture captureHead(EntityModel<LivingEntityRenderState> model) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (stack.isEmpty()) {
            return null;
        }
        CustomHeadLayer.Transforms transforms = config.headTransforms;
        if (stack.getItem() instanceof BlockItem block && block.getBlock() instanceof AbstractSkullBlock skull) {
            SpecialItemModels.SkullKey key = SpecialItemModels.skullKey(skull.getType(),
                    stack.get(DataComponents.PROFILE));
            if (key == null) {
                // Modded skull type: no known bake, and vanilla is suppressed -- hidden.
                return null;
            }
            PoseStack ps = handPose;
            ps.setIdentity();
            ps.scale(transforms.horizontalScale(), 1.0F, transforms.horizontalScale());
            model.root().translateAndRotate(ps);
            ((HeadedModel) model).translateToHead(ps);
            ps.translate(0.0F, transforms.skullYOffset(), 0.0F);
            ps.scale(1.1875F, 1.1875F, 1.1875F);
            return new HeadCapture(null, ItemStack.EMPTY,
                    List.of(new SpecialItemModels.Resolved(key, new Matrix4f())),
                    state.wornHeadAnimationPos, new Matrix4f(ps.last().pose()));
        }
        if (HumanoidArmorLayer.shouldRender(stack, EquipmentSlot.HEAD)) {
            return null;
        }
        PoseStack ps = handPose;
        ps.setIdentity();
        ps.scale(transforms.horizontalScale(), 1.0F, transforms.horizontalScale());
        model.root().translateAndRotate(ps);
        ((HeadedModel) model).translateToHead(ps);
        CustomHeadLayer.translateToHead(ps, transforms);
        return new HeadCapture(new Matrix4f(ps.last().pose()), stack, null, 0.0F, null);
    }

    private long evaluateConditions(LivingEntityRenderState state) {
        long mask = 0L;
        int bit = 0;
        for (Overlay overlay : config.overlays) {
            if (overlay.visible() == null || overlay.visible().test(state)) {
                mask |= 1L << bit;
            }
            bit++;
        }
        for (BlockDecoration decoration : config.blockDecorations) {
            if (decoration.visible() == null || decoration.visible().test(state)) {
                mask |= 1L << bit;
            }
            bit++;
        }
        return mask;
    }

    // Render thread: the model-space item transform for one hand, reproducing submitArmWithItem's prologue; null for an empty hand.
    @Nullable
    private Matrix4f handMatrix(HumanoidArm arm, ArmedEntityRenderState as) {
        ItemStack stack = arm == HumanoidArm.RIGHT ? as.rightHandItemStack : as.leftHandItemStack;
        if (stack.isEmpty()) {
            return null;
        }
        PoseStack ps = handPose;
        ps.setIdentity();
        ((ArmedModel) model(0)).translateToHand(as, arm, ps);
        ps.mulPose(Axis.XP.rotationDegrees(-90.0F));
        ps.mulPose(Axis.YP.rotationDegrees(180.0F));
        boolean babyOffset = as.isBaby && as.entityType != EntityTypes.ARMOR_STAND;
        float offsetX = (arm == HumanoidArm.LEFT ? -1.0F : 1.0F) * (babyOffset ? 0.0F : 1.0F);
        ps.translate(offsetX / 16.0F, (babyOffset ? 1.0F : 2.0F) / 16.0F, (babyOffset ? -4.5F : -10.0F) / 16.0F);
        if (as.attackTime > 0.0F && as.attackArm == arm && as.swingAnimationType == SwingAnimationType.STAB) {
            SpearAnimations.thirdPersonAttackItem(as, ps);
        }
        float ticksUsingItem = as.ticksUsingItem(arm);
        if (ticksUsingItem != 0.0F) {
            (arm == HumanoidArm.RIGHT ? as.rightArmPose : as.leftArmPose).animateUseItem(as, ps, ticksUsingItem, arm,
                    stack);
        }
        return new Matrix4f(ps.last().pose());
    }

    @Override
    protected void applyExtra(@Nullable Object captured, int variant, float[] t, boolean[] draw, Matrix4f root,
                              int light, int overlay) {
        LivingExtra snapshot = (LivingExtra) captured;
        if (heldItemsActive) {
            if (snapshot.heldItemsShown() && snapshot.hands() != null) {
                HandItems hands = snapshot.hands();
                applyHand(rightSlot, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, hands.rightLocal(), hands.rightStack(),
                        root, light);
                applyHand(leftSlot, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, hands.leftLocal(), hands.leftStack(),
                        root, light);
            } else {
                hideSlot(rightSlot);
                hideSlot(leftSlot);
            }
        }

        if (customHeldActive && snapshot.customHeld() != null) {
            CustomHeldCapture[] caps = snapshot.customHeld();
            for (int i = 0; i < customSlots.length; i++) {
                CustomHeldItem chi = config.customHeldItems.get(i);
                applyHand(customSlots[i], chi.context(), caps[i].local(), caps[i].stack(), root, light);
            }
        }

        if (headItemActive) {
            HeadCapture head = snapshot.head();
            if (head == null) {
                hideSlot(headSlot);
                hideWornHead();
            } else if (head.skull() != null) {
                hideSlot(headSlot);
                if (wornHead == null) {
                    wornHead = new InstancedSpecialItem(instancerProvider());
                }
                // CustomHeadLayer submits worn skulls + head items with NO_OVERLAY.
                wornHead.apply(head.skull(), head.skullAnimation(), new Matrix4f(root).mul(head.skullLocal()), light,
                        OverlayTexture.NO_OVERLAY);
            } else {
                hideWornHead();
                applyHand(headSlot, ItemDisplayContext.HEAD, head.itemLocal(), head.stack(), root, light);
            }
        }

        if (armorActive && snapshot.equipment() != null) {
            Equipment eq = snapshot.equipment();
            armor.apply(eq.head(), eq.chest(), eq.legs(), eq.feet(), t, root, light);
        }

        if (bodyEquipmentActive && snapshot.bodyEquipItems() != null) {
            ItemStack[] items = snapshot.bodyEquipItems();
            boolean[] ridden = snapshot.bodyEquipRidden();
            boolean[] fallback = snapshot.bodyEquipFallback();
            float[][] selfPose = snapshot.bodyEquipSelfPose();
            for (int i = 0; i < bodyEquip.length; i++) {
                bodyEquip[i].apply(items[i], ridden[i], fallback[i], selfPose == null ? null : selfPose[i], t, draw,
                        root, light);
            }
        }

        if (overlayLayer != null) {
            overlayLayer.apply(snapshot.conditionMask(), snapshot.overlayColors(), snapshot.overlayTextures(), t, root,
                    light, overlay);
        }

        if (blockDecos != null) {
            blockDecos.apply(snapshot.conditionMask(), config.overlays.size(), t, root, light, overlay);
        }

        if (blockDynamics != null) {
            blockDynamics.apply(snapshot.dynamicBlocks(), t, root, light, overlay);
        }
    }

    @Override
    protected void onBodyCreated(int variant, InstanceTree[] nodesInOrder) {
        if (overlaysActive && overlayLayer == null) {
            overlayLayer = new InstancedOverlayLayer(instancerProvider(), boneIndex, config.overlays);
        }
        if (blockDecorationsActive && blockDecos == null) {
            blockDecos = new InstancedBlockDecorations(instancerProvider(), entity, boneIndex, config.blockDecorations);
        }
        if (dynamicBlocksActive && blockDynamics == null) {
            int[][] bones = new int[config.dynamicBlocks.size()][];
            Matrix4fc[] offsets = new Matrix4fc[config.dynamicBlocks.size()];
            for (int i = 0; i < bones.length; i++) {
                DynamicBlock db = config.dynamicBlocks.get(i);
                bones[i] = resolveBonePath(db.bone(), boneIndex);
                offsets[i] = db.offset();
            }
            blockDynamics = new InstancedDynamicBlocks(instancerProvider(), bones, offsets);
        }

        int missing = 0;
        for (InstanceTree node : nodesInOrder) {
            if (node == null) {
                missing++;
            }
        }
        if (missing > 0 && MISMATCH_WARNED.add(entity.getType())) {
            LOGGER.warn(
                    "[living-visual] {}: {}/{} live bones have no matching baked instance node (layer {}) -- those parts won't render",
                    entity.getType().getDescriptionId(), missing, nodesInOrder.length, layer(variant));
        }
    }

    private void applyHand(HandSlot slot, ItemDisplayContext ctx, @Nullable Matrix4f local, ItemStack stack,
                           Matrix4f root, int light) {
        if (local == null || stack.isEmpty()) {
            hideSlot(slot);
            return;
        }
        if (!ItemStack.matches(slot.stack, stack)) {
            slot.instance = ItemModels.rebake(instancerProvider(), slot.instance, stack, ctx, entity, entity.getId());
            slot.stack = stack;
            slot.visible = slot.instance != null;
        }
        TransformedInstance inst = slot.instance;
        if (inst == null) {
            // Re-resolved per frame like vanilla (also picks up async player-skin loads).
            slot.specials = SpecialItemModels.resolve(stack, ctx, entity, entity.getId());
            if (slot.specials.isEmpty()) {
                hideSpecial(slot);
                return;
            }
            if (slot.special == null) {
                slot.special = new InstancedSpecialItem(instancerProvider());
            }
            slot.special.apply(slot.specials, 0.0F, new Matrix4f(root).mul(local), light, OverlayTexture.NO_OVERLAY);
            return;
        }
        hideSpecial(slot);
        // Reveal BEFORE writing: a hidden handle's slab pointer is the write-only trash slot; the reveal re-seeds an
        // identity pose, so writing first loses the pose (re-revealed held items flashed at the render origin).
        if (!slot.visible) {
            inst.setVisible(true);
            slot.visible = true;
        }
        inst.setTransform(new Matrix4f(root).mul(local));
        inst.light(light);
        inst.overlay(OverlayTexture.NO_OVERLAY);
        inst.setChanged();
    }

    private void hideWornHead() {
        if (wornHead != null) {
            wornHead.hide();
        }
    }

    @Override
    protected void hideExtra() {
        hideSlot(rightSlot);
        hideSlot(leftSlot);
        if (customSlots != null) {
            for (HandSlot slot : customSlots) {
                hideSlot(slot);
            }
        }
        hideSlot(headSlot);
        hideWornHead();
        if (armor != null) {
            armor.hide();
        }
        if (bodyEquip != null) {
            for (InstancedEquipmentLayer eq : bodyEquip) {
                eq.hide();
            }
        }
        if (overlayLayer != null) {
            overlayLayer.hide();
        }
        if (blockDecos != null) {
            blockDecos.hide();
        }
        if (blockDynamics != null) {
            blockDynamics.hide();
        }
    }

    private void createComponents() {
        fire = new FireComponent(visualizationContext, entity);
        shadow = new ShadowComponent(visualizationContext, entity).radius(renderer.shadowRadius);
        nameTag = new NameTagComponent(visualizationContext, entity);
    }

    private void deleteComponents() {
        if (fire != null) {
            fire.delete();
            fire = null;
        }
        if (shadow != null) {
            shadow.delete();
            shadow = null;
        }
        if (nameTag != null) {
            nameTag.delete();
            nameTag = null;
        }
    }

    @Override
    protected void _delete() {
        super._delete();
        deleteSlot(rightSlot);
        deleteSlot(leftSlot);
        if (customSlots != null) {
            for (HandSlot slot : customSlots) {
                deleteSlot(slot);
            }
        }
        deleteSlot(headSlot);
        if (wornHead != null) {
            wornHead.delete();
        }
        if (armor != null) {
            armor.delete();
        }
        if (bodyEquip != null) {
            for (InstancedEquipmentLayer eq : bodyEquip) {
                eq.delete();
            }
        }
        if (overlayLayer != null) {
            overlayLayer.delete();
        }
        if (blockDecos != null) {
            blockDecos.delete();
        }
        if (blockDynamics != null) {
            blockDynamics.delete();
        }
        deleteComponents();
    }

    enum OverlayKind {
        CUTOUT,
        COPLANAR,
        TRANSLUCENT,
        EMISSIVE,
        EMISSIVE_TRANSLUCENT
    }

    /**
     * Reproduces a mob's {@code scale()} hook on the root pose, post the {@code (-1,-1,1)} flip.
     */
    @FunctionalInterface
    public interface RootScale {
        void apply(LivingEntityRenderState state, PoseStack pose);
    }

    /**
     * Replaces the base {@code setupRotations} for mobs that override it; extend via {@link #baseRotations} first.
     */
    @FunctionalInterface
    public interface Rotations {
        void apply(PoseStack pose, LivingEntityRenderState state, float bodyRot, float entityScale);
    }

    /**
     * Reproduces {@code getWhiteOverlayProgress} (the creeper pre-explosion flash).
     */
    @FunctionalInterface
    public interface WhiteOverlay {
        float apply(LivingEntityRenderState state);
    }

    /**
     * Reproduces a per-state {@code getShadowRadius} override (pufferfish puff size).
     */
    @FunctionalInterface
    public interface ShadowRadius {
        float radius(LivingEntity entity);
    }

    /**
     * Reproduces a mob's {@code isShaking} override. Returns the FULL condition vanilla's method returns --
     * additive overrides include {@code state.isFullyFrozen}; the skeleton family replaces it outright.
     */
    @FunctionalInterface
    public interface Shaking {
        boolean shaking(LivingEntityRenderState state);
    }

    /**
     * Computes a custom held item's model-space transform from the freshly-posed model + render state. Walk the
     * bone chain with {@code model.root().getChild(name).translateAndRotate(poseStack)} and return the final matrix.
     */
    @FunctionalInterface
    public interface ItemPose {
        Matrix4f pose(EntityModel model, LivingEntityRenderState state);
    }

    // Dynamic overlay materials must be shared per (texture, kind) -- an unshared SimpleMaterial cache-misses per entity.
    private record DynamicKey(Identifier texture, OverlayKind kind) {
    }

    public record ModelVariant(ModelLayerLocation layer, Function<ModelPart, ? extends EntityModel<?>> factory) {
    }

    public record Overlay(ModelLayerLocation layer, @Nullable Material material, boolean emissive,
                          @Nullable Predicate<LivingEntityRenderState> visible,
                          @Nullable ToIntFunction<LivingEntityRenderState> color,
                          @Nullable Function<LivingEntityRenderState, Identifier> textureResolver,
                          @Nullable OverlayKind dynamicKind) {
    }

    public record CustomHeldItem(Function<LivingEntity, ItemStack> stack, ItemPose pose,
                                 ItemDisplayContext context, @Nullable Predicate<LivingEntityRenderState> visible) {
    }

    private record CustomHeldCapture(@Nullable Matrix4f local, ItemStack stack) {
    }

    public record BlockDecoration(Function<LivingEntity, BlockState> state, List<BlockPlacement> placements,
                                  @Nullable Predicate<LivingEntityRenderState> visible) {
    }

    public record BlockPlacement(@Nullable String bone, Matrix4fc offset) {
    }

    public record DynamicBlock(Function<LivingEntity, BlockState> state, @Nullable String bone, Matrix4fc offset) {
    }

    public record BodyEquipment(Function<LivingEntity, ItemStack> item, EquipmentClientInfo.LayerType layerType,
                                ModelLayerLocation modelLayer, @Nullable Predicate<LivingEntityRenderState> ridden,
                                @Nullable Function<ItemStack, Identifier> crackTexture,
                                @Nullable ResourceKey<EquipmentAsset> fallbackAsset,
                                @Nullable Predicate<LivingEntityRenderState> fallbackWhen,
                                RiddenPose @Nullable [] riddenPoses,
                                @Nullable Function<ModelPart, ? extends EntityModel<?>> selfAnimated,
                                @Nullable Matrix4fc rootOffset) {
    }

    public record RiddenPose(String bone, PartPose ridden, PartPose notRidden) {
    }

    private static final class HandSlot {
        @Nullable
        TransformedInstance instance;
        ItemStack stack = ItemStack.EMPTY;
        boolean visible;
        List<SpecialItemModels.Resolved> specials = List.of();
        @Nullable
        InstancedSpecialItem special;
    }

    private record HandItems(@Nullable Matrix4f rightLocal, ItemStack rightStack,
                             @Nullable Matrix4f leftLocal, ItemStack leftStack) {
    }

    private record Equipment(ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
    }

    private record LivingExtra(@Nullable HandItems hands, @Nullable Equipment equipment,
                               long conditionMask, boolean heldItemsShown, BlockState @Nullable [] dynamicBlocks,
                               int @Nullable [] overlayColors, Identifier @Nullable [] overlayTextures,
                               CustomHeldCapture @Nullable [] customHeld, ItemStack @Nullable [] bodyEquipItems,
                               boolean @Nullable [] bodyEquipRidden, boolean @Nullable [] bodyEquipFallback,
                               float @Nullable [] @Nullable [] bodyEquipSelfPose, @Nullable HeadCapture head) {
    }

    private record HeadCapture(@Nullable Matrix4f itemLocal, ItemStack stack,
                               @Nullable List<SpecialItemModels.Resolved> skull, float skullAnimation,
                               @Nullable Matrix4f skullLocal) {
    }

    /**
     * Per-registration description of a living-entity visual; built via {@link #builder}.
     */
    public static final class Config {
        final ModelLayerLocation layer;
        @Nullable
        final Function<ModelPart, ? extends EntityModel<?>> modelFactory;
        @Nullable
        final List<ModelVariant> modelVariants;
        @Nullable
        final ToIntFunction<LivingEntityRenderState> variantSelector;
        @Nullable
        final ToIntFunction<LivingEntityRenderState> bodyColor;
        @Nullable
        final ShadowRadius shadowRadius;
        @Nullable
        final Function<LivingEntityRenderState, Identifier> bodyTexture;
        final boolean handlesBaby;
        @Nullable
        final Predicate<LivingEntity> vanillaFallback;
        final boolean translucentBody;
        final float flipDegrees;
        @Nullable
        final RootScale scale;
        @Nullable
        final Rotations rotations;
        @Nullable
        final Shaking shaking;
        @Nullable
        final WhiteOverlay whiteOverlay;
        final boolean heldItems;
        @Nullable
        final Predicate<LivingEntityRenderState> heldItemsVisible;
        final CustomHeadLayer.@Nullable Transforms headTransforms;
        @Nullable
        final ArmorModelSet<ModelLayerLocation> armorSet;
        final boolean armorBaby;
        final List<Overlay> overlays;
        final List<BlockDecoration> blockDecorations;
        final List<DynamicBlock> dynamicBlocks;
        final List<CustomHeldItem> customHeldItems;
        final List<BodyEquipment> bodyEquipment;

        private Config(Builder b) {
            this.layer = b.layer;
            this.modelFactory = b.modelFactory;
            this.modelVariants = b.modelVariants == null ? null : List.copyOf(b.modelVariants);
            this.variantSelector = b.variantSelector;
            this.bodyColor = b.bodyColor;
            this.shadowRadius = b.shadowRadius;
            this.bodyTexture = b.bodyTexture;
            this.handlesBaby = b.handlesBaby;
            this.vanillaFallback = b.vanillaFallback;
            this.translucentBody = b.translucentBody;
            this.flipDegrees = b.flipDegrees;
            this.scale = b.scale;
            this.rotations = b.rotations;
            this.shaking = b.shaking;
            this.whiteOverlay = b.whiteOverlay;
            this.heldItems = b.heldItems;
            this.heldItemsVisible = b.heldItemsVisible;
            this.headTransforms = b.headTransforms;
            this.armorSet = b.armorSet;
            this.armorBaby = b.armorBaby;
            this.overlays = List.copyOf(b.overlays);
            this.blockDecorations = List.copyOf(b.blockDecorations);
            this.dynamicBlocks = List.copyOf(b.dynamicBlocks);
            this.customHeldItems = List.copyOf(b.customHeldItems);
            this.bodyEquipment = List.copyOf(b.bodyEquipment);
        }

        public static Builder builder(ModelLayerLocation layer) {
            return new Builder(layer);
        }

        /**
         * True on frames vanilla must draw this entity whole: babies unless {@link Builder#handlesBaby}, invisible
         * entities, and the per-mob fallback. {@code skipVanillaRender} must be its exact complement.
         */
        public boolean vanillaHandles(LivingEntity entity) {
            return (!handlesBaby && entity.isBaby()) || entity.isInvisible()
                    || (vanillaFallback != null && vanillaFallback.test(entity));
        }

        public static final class Builder {
            private final ModelLayerLocation layer;
            private final List<Overlay> overlays = new ArrayList<>();
            private final List<BlockDecoration> blockDecorations = new ArrayList<>();
            private final List<DynamicBlock> dynamicBlocks = new ArrayList<>();
            private final List<CustomHeldItem> customHeldItems = new ArrayList<>();
            private final List<BodyEquipment> bodyEquipment = new ArrayList<>();
            @Nullable
            private Function<ModelPart, ? extends EntityModel<?>> modelFactory;
            @Nullable
            private List<ModelVariant> modelVariants;
            @Nullable
            private ToIntFunction<LivingEntityRenderState> variantSelector;
            @Nullable
            private ToIntFunction<LivingEntityRenderState> bodyColor;
            @Nullable
            private ShadowRadius shadowRadius;
            @Nullable
            private Function<LivingEntityRenderState, Identifier> bodyTexture;
            private boolean handlesBaby;
            @Nullable
            private Predicate<LivingEntity> vanillaFallback;
            private boolean translucentBody;
            private float flipDegrees = 90.0F;
            @Nullable
            private RootScale scale;
            @Nullable
            private Rotations rotations;
            @Nullable
            private Shaking shaking;
            @Nullable
            private WhiteOverlay whiteOverlay;
            private boolean heldItems;
            @Nullable
            private Predicate<LivingEntityRenderState> heldItemsVisible;
            private CustomHeadLayer.@Nullable Transforms headTransforms;
            @Nullable
            private ArmorModelSet<ModelLayerLocation> armorSet;
            private boolean armorBaby;

            private Builder(ModelLayerLocation layer) {
                this.layer = layer;
            }

            public Builder flipDegrees(float flipDegrees) {
                this.flipDegrees = flipDegrees;
                return this;
            }

            /**
             * Build the body model from the config's layer bake instead of {@code renderer.getModel()}. REQUIRED for
             * mobs whose renderer swaps {@code this.model} per submit.
             */
            public Builder modelFactory(Function<ModelPart, ? extends EntityModel<?>> factory) {
                this.modelFactory = factory;
                return this;
            }

            /**
             * Swap-model mob: the selector picks the active body variant per frame (pufferfish puff states); the
             * body InstanceTree rebuilds on change. Does not compose with decoration layers.
             */
            public Builder modelVariants(ToIntFunction<LivingEntityRenderState> selector, ModelVariant... variants) {
                this.variantSelector = selector;
                this.modelVariants = List.of(variants);
                return this;
            }

            public Builder bodyColor(ToIntFunction<LivingEntityRenderState> color) {
                this.bodyColor = color;
                return this;
            }

            public Builder shadowRadius(ShadowRadius radius) {
                this.shadowRadius = radius;
                return this;
            }

            /**
             * Override the body texture (default: the renderer's {@code getTextureLocation}); a null result falls
             * back to the renderer's.
             */
            public Builder bodyTexture(Function<LivingEntityRenderState, Identifier> bodyTexture) {
                this.bodyTexture = bodyTexture;
                return this;
            }

            public Builder handlesBaby() {
                this.handlesBaby = true;
                return this;
            }

            /**
             * Frames where the entity keeps vanilla rendering entirely (a beaming guardian's beam is custom
             * non-instanceable geometry); {@code living()} folds this into {@code skipVanillaRender}.
             */
            public Builder vanillaFallback(Predicate<LivingEntity> fallback) {
                this.vanillaFallback = fallback;
                return this;
            }

            public Builder translucentBody() {
                this.translucentBody = true;
                return this;
            }

            public Builder scale(RootScale scale) {
                this.scale = scale;
                return this;
            }

            public Builder rotations(Rotations rotations) {
                this.rotations = rotations;
                return this;
            }

            public Builder shaking(Shaking shaking) {
                this.shaking = shaking;
                return this;
            }

            public Builder whiteOverlay(WhiteOverlay whiteOverlay) {
                this.whiteOverlay = whiteOverlay;
                return this;
            }

            public Builder heldItems() {
                this.heldItems = true;
                return this;
            }

            public Builder heldItems(Predicate<LivingEntityRenderState> visibleWhen) {
                this.heldItems = true;
                this.heldItemsVisible = visibleWhen;
                return this;
            }

            public Builder headItem() {
                return headItem(CustomHeadLayer.Transforms.DEFAULT);
            }

            public Builder headItem(CustomHeadLayer.Transforms transforms) {
                this.headTransforms = transforms;
                return this;
            }

            public Builder dynamicBlock(Function<LivingEntity, BlockState> state, @Nullable String bone,
                                        Matrix4fc offset) {
                dynamicBlocks.add(new DynamicBlock(state, bone, offset));
                return this;
            }

            public Builder armor(ArmorModelSet<ModelLayerLocation> armorSet) {
                this.armorSet = armorSet;
                return this;
            }

            public Builder babyArmor(ArmorModelSet<ModelLayerLocation> armorSet) {
                this.armorSet = armorSet;
                this.armorBaby = true;
                return this;
            }

            private Builder addOverlay(ModelLayerLocation layer, Material material, boolean emissive,
                                       @Nullable Predicate<LivingEntityRenderState> visibleWhen,
                                       @Nullable ToIntFunction<LivingEntityRenderState> color) {
                overlays.add(new Overlay(layer, material, emissive, visibleWhen, color, null, null));
                return this;
            }

            public Builder texturedCoplanarOverlay(ModelLayerLocation layer,
                                                   Function<LivingEntityRenderState, Identifier> texture,
                                                   @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                overlays.add(new Overlay(layer, null, false, visibleWhen, null, texture, OverlayKind.COPLANAR));
                return this;
            }

            public Builder texturedTranslucentOverlay(ModelLayerLocation layer,
                                                      Function<LivingEntityRenderState, Identifier> texture,
                                                      @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                overlays.add(new Overlay(layer, null, false, visibleWhen, null, texture, OverlayKind.TRANSLUCENT));
                return this;
            }

            public Builder texturedColoredCoplanarOverlay(ModelLayerLocation layer,
                                                          Function<LivingEntityRenderState, Identifier> texture,
                                                          ToIntFunction<LivingEntityRenderState> color,
                                                          @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                overlays.add(new Overlay(layer, null, false, visibleWhen, color, texture, OverlayKind.COPLANAR));
                return this;
            }

            private Builder addOverlay(ModelLayerLocation layer, Identifier texture, OverlayKind kind, boolean emissive,
                                       @Nullable Predicate<LivingEntityRenderState> visibleWhen,
                                       @Nullable ToIntFunction<LivingEntityRenderState> color) {
                return addOverlay(layer, overlayMaterial(texture, kind), emissive, visibleWhen, color);
            }

            public Builder overlay(ModelLayerLocation layer, Identifier texture) {
                return addOverlay(layer, texture, OverlayKind.CUTOUT, false, null, null);
            }

            public Builder overlay(ModelLayerLocation layer, Identifier texture,
                                   Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.CUTOUT, false, visibleWhen, null);
            }

            public Builder coplanarOverlay(ModelLayerLocation layer, Identifier texture,
                                           Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.COPLANAR, false, visibleWhen, null);
            }

            public Builder translucentOverlay(ModelLayerLocation layer, Identifier texture) {
                return addOverlay(layer, texture, OverlayKind.TRANSLUCENT, false, null, null);
            }

            public Builder translucentOverlay(ModelLayerLocation layer, Identifier texture,
                                              Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.TRANSLUCENT, false, visibleWhen, null);
            }

            public Builder emissiveOverlay(ModelLayerLocation layer, Identifier texture) {
                return addOverlay(layer, texture, OverlayKind.EMISSIVE, true, null, null);
            }

            public Builder emissiveOverlay(ModelLayerLocation layer, Identifier texture,
                                           Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.EMISSIVE, true, visibleWhen, null);
            }

            public Builder texturedEmissiveOverlay(ModelLayerLocation layer,
                                                   Function<LivingEntityRenderState, Identifier> texture,
                                                   @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                overlays.add(new Overlay(layer, null, true, visibleWhen, null, texture, OverlayKind.EMISSIVE));
                return this;
            }

            public Builder coloredOverlay(ModelLayerLocation layer, Identifier texture,
                                          ToIntFunction<LivingEntityRenderState> color,
                                          @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.CUTOUT, false, visibleWhen, color);
            }

            public Builder coloredCoplanarOverlay(ModelLayerLocation layer, Identifier texture,
                                                  ToIntFunction<LivingEntityRenderState> color,
                                                  @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.COPLANAR, false, visibleWhen, color);
            }

            public Builder emissiveTranslucentOverlay(ModelLayerLocation layer, Identifier texture,
                                                      ToIntFunction<LivingEntityRenderState> color,
                                                      @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, texture, OverlayKind.EMISSIVE_TRANSLUCENT, true, visibleWhen, color);
            }

            public Builder scrollOverlay(ModelLayerLocation layer, Material material,
                                         @Nullable ToIntFunction<LivingEntityRenderState> color,
                                         @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                return addOverlay(layer, material, false, visibleWhen, color);
            }

            public Builder customHeldItem(Function<LivingEntity, ItemStack> stack, ItemPose pose,
                                          ItemDisplayContext context,
                                          @Nullable Predicate<LivingEntityRenderState> visibleWhen) {
                customHeldItems.add(new CustomHeldItem(stack, pose, context, visibleWhen));
                return this;
            }

            public Builder bodyEquipment(Function<LivingEntity, ItemStack> item,
                                         EquipmentClientInfo.LayerType layerType,
                                         ModelLayerLocation modelLayer) {
                bodyEquipment.add(
                        new BodyEquipment(item, layerType, modelLayer, null, null, null, null, null, null, null));
                return this;
            }

            public Builder bodyEquipment(Function<LivingEntity, ItemStack> item,
                                         EquipmentClientInfo.LayerType layerType,
                                         ModelLayerLocation modelLayer,
                                         @Nullable Predicate<LivingEntityRenderState> ridden,
                                         @Nullable Function<ItemStack, Identifier> crackTexture) {
                bodyEquipment.add(
                        new BodyEquipment(item, layerType, modelLayer, ridden, crackTexture, null, null, null, null,
                                null));
                return this;
            }

            public Builder bodyEquipmentPosed(Function<LivingEntity, ItemStack> item,
                                              EquipmentClientInfo.LayerType layerType,
                                              ModelLayerLocation modelLayer, Predicate<LivingEntityRenderState> ridden,
                                              RiddenPose... riddenPoses) {
                bodyEquipment.add(
                        new BodyEquipment(item, layerType, modelLayer, ridden, null, null, null, riddenPoses, null,
                                null));
                return this;
            }

            public Builder bodyEquipment(Function<LivingEntity, ItemStack> item,
                                         EquipmentClientInfo.LayerType layerType,
                                         ModelLayerLocation modelLayer, ResourceKey<EquipmentAsset> fallbackAsset,
                                         @Nullable Predicate<LivingEntityRenderState> fallbackWhen) {
                bodyEquipment.add(
                        new BodyEquipment(item, layerType, modelLayer, null, null, fallbackAsset, fallbackWhen, null,
                                null, null));
                return this;
            }

            public Builder bodyEquipmentSelfAnimated(Function<LivingEntity, ItemStack> item,
                                                     EquipmentClientInfo.LayerType layerType,
                                                     ModelLayerLocation modelLayer,
                                                     Function<ModelPart, ? extends EntityModel<?>> model,
                                                     @Nullable Matrix4fc rootOffset) {
                bodyEquipment.add(new BodyEquipment(item, layerType, modelLayer, null, null, null, null, null, model,
                        rootOffset));
                return this;
            }

            public Builder elytra() {
                return elytra(ModelLayers.ELYTRA);
            }

            public Builder elytra(ModelLayerLocation elytraLayer) {
                return bodyEquipmentSelfAnimated(e -> e.getItemBySlot(EquipmentSlot.CHEST),
                        EquipmentClientInfo.LayerType.WINGS,
                        elytraLayer, ElytraModel::new, WINGS_OFFSET);
            }

            public Builder blockDecoration(Function<LivingEntity, BlockState> state, BlockPlacement... placements) {
                blockDecorations.add(new BlockDecoration(state, List.of(placements), null));
                return this;
            }

            public Builder blockDecoration(Predicate<LivingEntityRenderState> visibleWhen,
                                           Function<LivingEntity, BlockState> state, BlockPlacement... placements) {
                blockDecorations.add(new BlockDecoration(state, List.of(placements), visibleWhen));
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }
}
