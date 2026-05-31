package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.model.part.PartPose;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Instanced equivalent of {@code RenderLivingBase}: bakes a {@link ModelBase}'s parts into one shared,
 * batched {@code ModelTree} (so all entities of a type collapse to ~one instanced draw per bone) and
 * reposes them each frame from a per-visual scratch model.
 *
 * <p>Downstream subclasses one of these per renderer family, passing an {@link EntityModel} (geometry +
 * part enumeration), the {@link Material} (texture), and a stable {@code cacheKey} for the geometry cache.
 * Override {@link #applyRotations} / {@link #preRenderCallback} to reproduce a specific renderer's root
 * transform (death spin, baby/size scale, sleeping pose); the defaults match vanilla {@code RenderLivingBase}.
 *
 * <p>The scratch model is per-visual on purpose: {@code beginFrame} runs concurrently across visuals and
 * {@code setRotationAngles} mutates shared {@code ModelRenderer} state, so a shared model would race.
 */
public abstract class AbstractLivingEntityVisual<T extends EntityLivingBase, M extends ModelBase>
        extends AbstractEntityVisual<T> implements SimpleDynamicVisual {
    // Phase 7 LOD: the bone-repose threshold scales with camera distance — exact within FULL_DETAIL_DIST, then
    // grows to MAX_REPOSE_EPS so a distant mob's sub-pixel idle sway stops flagging bones changed (so its
    // already-posed instances skip the per-frame re-upload). Recomputed each frame in beginFrame.
    private static final float FULL_DETAIL_DIST_SQ = 24.0F * 24.0F;
    private static final float MAX_REPOSE_EPS = 0.05F;
    private float reposeEps = 0.0F;

    protected final M model;
    protected final InstanceTree instances;
    protected final ModelRenderer[] bones;
    private final EntityModel<M> entityModel;

    private final Matrix4f pose = new Matrix4f();
    private final Matrix4f babyPose = new Matrix4f();
    private final Matrix4f lastRoot = new Matrix4f();
    private boolean wasBaby;
    private final float shadowRadius;
    private FireComponent fire;
    private ShadowComponent shadow;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;
    private int lastColor = 0;
    private VariantAtlas.Cell lastUvRegion = null;
    private boolean hidden;
    private boolean posed;
    // Set when a subclass toggles a bone's skipDraw this frame (via setRootSkipDraw). A reveal only flags the
    // node changed; the per-frame push is otherwise gated on motion, so an idle-frame reveal (saddle a stationary
    // horse, chest a stationary donkey) would never flush — the bone would ghost at the render origin. OR this
    // into both the static-update gate (pose) and the instance-state gate (the reseeded slot has light 0) so
    // the reveal lands the same frame regardless of motion.
    private boolean structureChanged;
    // The first frame after (re)creation or a reveal MUST push pose+light regardless of the distance limiter.
    // On the indirect backend a freshly-seeded slot is drawable immediately (identity pose = render origin,
    // light 0 = a gray-black ghost), and the banded limiter can otherwise defer the real data for many ticks.
    private boolean forcePush = true;
    private final List<LivingLayer> layers = new ArrayList<>();

    protected AbstractLivingEntityVisual(VisualizationContext ctx, T entity, float partialTick,
                                         EntityModel<M> entityModel, Material material, String cacheKey, float shadowRadius) {
        super(ctx, entity, partialTick);
        this.shadowRadius = shadowRadius;
        this.entityModel = entityModel;
        this.model = entityModel.create();
        this.bones = entityModel.roots(model);
        this.instances = InstanceTree.create(instancerProvider(), buildTree(entityModel, material, cacheKey), 0, instanceType());
        updatePose(partialTick);
    }

    /** Build the shared, batched geometry tree for an {@link EntityModel} (one cached {@link ModelTrees}
     *  entry per root part, keyed by {@code cacheKey}). Exposed for {@link LivingLayer}s that re-instance a
     *  different model over the body — e.g. sheep wool, armor — and copy the body's posed bones one-to-one. */
    public static <M extends ModelBase> ModelTree buildTree(EntityModel<M> entityModel, Material material, String cacheKey) {
        int rootCount = entityModel.roots(entityModel.create()).length;
        ModelTree[] children = new ModelTree[rootCount];
        for (int i = 0; i < rootCount; i++) {
            int idx = i;
            children[i] = ModelTrees.of(cacheKey + ":" + idx, () -> entityModel.roots(entityModel.create())[idx], material);
        }
        return new ModelTree(null, PartPose.ZERO, children);
    }

    /** Add a render layer (eyes, held item, dyed overlay). Call from a subclass constructor. */
    protected void addLayer(LivingLayer layer) {
        layers.add(layer);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (shouldHide()) {
            if (!hidden) {
                hidden = true;
                instances.visible(false);
                for (int i = 0, n = layers.size(); i < n; i++) {
                    layers.get(i).setVisible(false);
                }
                // Hidden entities fall back to vanilla, which draws their fire/shadow; drop ours to avoid doubling.
                if (fire != null) {
                    fire.delete();
                    fire = null;
                }
                if (shadow != null) {
                    shadow.delete();
                    shadow = null;
                }
            }
            return;
        }
        if (!isVisible(ctx.frustum())) {
            // Un-hiding waits here too: the reveal below reseeds the slots (identity pose at the render
            // origin), so a culled entity must stay hidden until updatePose can actually run.
            return;
        }
        if (hidden) {
            hidden = false;
            instances.visible(true);
            for (int i = 0, n = layers.size(); i < n; i++) {
                layers.get(i).setVisible(true);
            }
            // Revealing (e.g. a baby growing up) reseeds the freed slab slots to the instance-type
            // default (identity pose, white color, light 0); force the next updatePose to re-push the
            // full pose and light/overlay/color, and bypass the limiter so it lands before the reseeded
            // slot is drawn (else it ghosts at the render origin — see forcePush).
            posed = false;
            lastLight = Integer.MIN_VALUE;
            lastOverlay = -1;
            lastColor = 0;
            lastUvRegion = null;
            forcePush = true;
        }
        // Fire and shadow are cheap and update every frame, so drive them ahead of the distance-limiter gate.
        if (fire == null) {
            fire = new FireComponent(visualizationContext, entity);
        }
        fire.beginFrame(ctx);
        if (shadow == null) {
            shadow = new ShadowComponent(visualizationContext, entity).radius(shadowRadius);
        }
        shadow.beginFrame(ctx);
        Vec3d cam = ctx.camera().getPosition();
        double distSq = distanceSquared(cam.x, cam.y, cam.z);
        reposeEps = distSq <= FULL_DETAIL_DIST_SQ ? 0.0F
                : Math.min(MAX_REPOSE_EPS, ((float) Math.sqrt(distSq) - 24.0F) * (MAX_REPOSE_EPS / 40.0F));
        if (!forcePush && !ctx.limiter().shouldUpdate(distSq)) {
            return;
        }
        forcePush = false;
        updatePose(ctx.partialTick());
    }

    protected void updatePose(float partialTick) {
        boolean baby = instancesBabies() && entity.isChild();
        poseModel(partialTick);
        buildRoot(pose, partialTick);

        boolean rootMoved = !posed || baby != wasBaby || !pose.equals(lastRoot, 1.0e-6F);
        boolean bonesMoved = repose();
        // True iff the body re-uploaded ⇒ copyPose layers may skip their own re-upload (see LivingLayer).
        boolean bodyMoved = rootMoved || bonesMoved || structureChanged;
        if (baby) {
            // Each root carries its own baby group transform, so the groups can't share one root matrix.
            if (rootMoved) {
                updateBabyRoots(true);
                lastRoot.set(pose);
                posed = true;
            } else if (bonesMoved || structureChanged) {
                updateBabyRoots(false);
            }
        } else if (rootMoved) {
            // Root (entity position/rotation) changed ⇒ every bone's world matrix changes ⇒ re-upload all.
            instances.updateInstances(pose);
            lastRoot.set(pose);
            posed = true;
        } else if (bonesMoved || structureChanged) {
            // Entity stationary but animating (or a bone just revealed) ⇒ re-upload the changed bones.
            instances.updateInstancesStatic(pose);
        }
        wasBaby = baby;

        int light = computePackedLight(partialTick);
        int overlay = overlayCoord(entity, partialTick);
        int color = tintColor(entity);
        VariantAtlas.Cell region = uvRegion(entity);
        // structureChanged: a reveal reseeds the slot to light 0 (pitch black), so the state push can't be
        // gated on deltas alone — an idle mob in stable light would never re-light the revealed bone.
        if (structureChanged || light != lastLight || overlay != lastOverlay || color != lastColor || region != lastUvRegion) {
            lastLight = light;
            lastOverlay = overlay;
            lastColor = color;
            lastUvRegion = region;
            applyInstanceState(instances, overlay, light, color, region);
        }
        structureChanged = false;

        for (int i = 0, n = layers.size(); i < n; i++) {
            layers.get(i).beginFrame(pose, light, partialTick, bodyMoved);
        }
    }

    // Walk the bone tree applying per-frame light/overlay/color/uv without allocating a capturing lambda each frame.
    private static void applyInstanceState(InstanceTree node, int overlay, int light, int color, VariantAtlas.Cell region) {
        TransformedInstance inst = node.instance();
        if (inst != null) {
            inst.overlay(overlay);
            inst.light(light);
            inst.colorArgb(color);
            if (region != null) {
                // Fail fast (CCE) when uvRegion() is overridden without instanceType() -> UV_TRANSFORMED;
                // a silent skip would render the whole atlas with no error.
                ((UvTransformedInstance) inst).uvRegion(region.offU(), region.offV(), region.scaleU(), region.scaleV());
            }
            inst.setChanged();
        }
        for (int i = 0, n = node.childCount(); i < n; i++) {
            applyInstanceState(node.child(i), overlay, light, color, region);
        }
    }

    /** Drive the scratch model's angles, mirroring {@code RenderLivingBase.doRender}. */
    protected void poseModel(float partialTick) {
        float headYaw = lerpAngle(entity.prevRotationYawHead, entity.rotationYawHead, partialTick);
        float netHeadYaw = headYaw - bodyYaw(partialTick);
        float headPitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTick;
        float ageInTicks = handleRotationFloat(partialTick);

        boolean riding = entity.isRiding();
        float limbSwingAmount = 0.0F;
        float limbSwing = 0.0F;
        if (!riding) {
            limbSwingAmount = entity.prevLimbSwingAmount + (entity.limbSwingAmount - entity.prevLimbSwingAmount) * partialTick;
            limbSwing = entity.limbSwing - entity.limbSwingAmount * (1.0F - partialTick);
            if (entity.isChild()) {
                limbSwing *= 3.0F;
            }
            if (limbSwingAmount > 1.0F) {
                limbSwingAmount = 1.0F;
            }
        }

        model.swingProgress = entity.getSwingProgress(partialTick);
        model.isRiding = riding && entity.getRidingEntity() != null && entity.getRidingEntity().shouldRiderSit();
        // Baby scale is applied per root in updateBabyRoots; the scratch model stays adult-sized.
        model.isChild = false;
        model.setLivingAnimations(entity, limbSwing, limbSwingAmount, partialTick);
        model.setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, 0.0625F, entity);
    }

    /** Build the model-space-to-render-origin matrix, mirroring {@code renderLivingAt} + {@code applyRotations}
     *  + {@code prepareScale}. The 0.0625 model scale is already baked into the bone meshes and poses. */
    protected void buildRoot(Matrix4f dest, float partialTick) {
        translateToInterpolatedPosition(dest, partialTick);

        applyRotations(dest, bodyYaw(partialTick), partialTick);
        dest.scale(-1.0F, -1.0F, 1.0F);
        preRenderCallback(dest, partialTick);
        applyModelTransform(dest);
        dest.translate(0.0F, -1.501F, 0.0F);
    }

    /** Root transform applied inside the (flipped) model frame, before the baked {@code -1.501} origin lift —
     *  i.e. the scope of vanilla {@code ModelBase.render}'s own {@code pushMatrix}/translate. Default: none.
     *  {@code ModelBiped} drops the body 0.2 here when sneaking (see {@link BipedLivingEntityVisual}). */
    protected void applyModelTransform(Matrix4f dest) {
    }

    /** Interpolated body yaw, used for both the root rotation and {@code netHeadYaw}. For a seated passenger of a
     *  living mount, re-bases on the mount's body yaw and clamps the head to ±85° of it (vanilla {@code RenderLivingBase}). */
    protected float bodyYaw(float partialTick) {
        float bodyYaw = lerpAngle(entity.prevRenderYawOffset, entity.renderYawOffset, partialTick);
        Entity mount = entity.getRidingEntity();
        if (entity.isRiding() && mount != null && mount.shouldRiderSit() && mount instanceof EntityLivingBase living) {
            float headYaw = lerpAngle(entity.prevRotationYawHead, entity.rotationYawHead, partialTick);
            bodyYaw = lerpAngle(living.prevRenderYawOffset, living.renderYawOffset, partialTick);
            float rel = MathHelper.wrapDegrees(headYaw - bodyYaw);
            if (rel < -85.0F) {
                rel = -85.0F;
            }
            if (rel >= 85.0F) {
                rel = 85.0F;
            }
            bodyYaw = headYaw - rel;
            if (rel * rel > 2500.0F) {
                bodyYaw += rel * 0.2F;
            }
        }
        return bodyYaw;
    }

    protected void applyRotations(Matrix4f dest, float bodyYaw, float partialTick) {
        dest.rotateY((float) Math.toRadians(180.0F - bodyYaw));
        if (entity.deathTime > 0) {
            float f = (entity.deathTime + partialTick - 1.0F) / 20.0F * 1.6F;
            f = MathHelper.sqrt(f);
            if (f > 1.0F) {
                f = 1.0F;
            }
            dest.rotateZ((float) Math.toRadians(f * getDeathMaxRotation()));
        }
    }

    protected float getDeathMaxRotation() {
        return 90.0F;
    }

    /** The third {@code setRotationAngles} argument (vanilla {@code RenderLivingBase.handleRotationFloat}).
     *  Default {@code ticksExisted + partialTick}; override where a model repurposes it (e.g. chicken wing flap). */
    protected float handleRotationFloat(float partialTick) {
        return entity.ticksExisted + partialTick;
    }

    /** Insert per-renderer scale (baby/slime/etc.) between the {@code (-1,-1,1)} flip and the vertical
     *  offset, mirroring {@code RenderLivingBase.preRenderCallback}. Default: none. */
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
    }

    /** Toggle a root bone's {@code skipDraw} from {@link #poseModel} (chest boxes, saddle tack). Records a
     *  structure change on an actual flip so the reveal flushes even on an idle frame — see {@link #structureChanged}. */
    protected void setRootSkipDraw(int rootIndex, boolean skipDraw) {
        InstanceTree node = instances.child(rootIndex);
        if (node.skipDraw() != skipDraw) {
            node.skipDraw(skipDraw);
            structureChanged = true;
        }
    }

    private boolean repose() {
        boolean any = false;
        for (int i = 0; i < bones.length; i++) {
            any |= reposeTree(instances.child(i), bones[i]);
        }
        return any;
    }

    private void updateBabyRoots(boolean force) {
        for (int i = 0; i < bones.length; i++) {
            babyPose.set(pose);
            entityModel.babyTransform(babyPose, model, i);
            if (force) {
                instances.child(i).updateInstances(babyPose);
            } else {
                instances.child(i).updateInstancesStatic(babyPose);
            }
        }
    }

    private boolean reposeTree(InstanceTree node, ModelRenderer renderer) {
        boolean any = node.copyTransformIfChanged(renderer, reposeEps);
        List<ModelRenderer> kids = renderer.childModels;
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                any |= reposeTree(node.child(i), kids.get(i));
            }
        }
        return any;
    }

    /** Whether to fall back to vanilla this frame. Override for variants the visual can't represent; pair the
     *  same condition with {@code skipVanillaRender} so vanilla draws it. */
    protected boolean shouldHide() {
        return entity.isInvisible() || (entity.isChild() && !instancesBabies());
    }

    /** Whether to instance babies via {@link EntityModel#babyTransform} instead of vanilla fallback. Requires
     *  {@link EntityModel#hasBabyTransform}; drop the {@code !isChild()} guard from {@code skipVanillaRender}
     *  to stay the exact complement. Pose-mirroring layers are baby-safe via
     *  {@code InstanceTree.copyComposedFrom}; layers that consume {@code rootPose} directly are not (see
     *  {@link LivingLayer}). */
    protected boolean instancesBabies() {
        return false;
    }

    /** Override to render this family on {@link InstanceTypes#UV_TRANSFORMED} (atlas variants). Default
     *  {@link InstanceTypes#TRANSFORMED}. Chosen once at construction. */
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.TRANSFORMED;
    }

    /** Per-instance atlas sub-rect for variant texturing; {@code null} (default) leaves the instance-type
     *  identity (full texture). Read each frame — a variant (e.g. villager profession) can change at runtime.
     *  A non-null return requires {@link #instanceType()} to be {@link InstanceTypes#UV_TRANSFORMED}, and MUST
     *  be a stable instance per variant (it is dirty-tracked by reference). */
    protected VariantAtlas.Cell uvRegion(T entity) {
        return null;
    }

    /** Per-instance multiplicative tint for dyed/colored variants. Default white = no tint. Hurt/death
     *  flashing is handled by the overlay texture (which can add red), not this. */
    protected int tintColor(T entity) {
        return 0xFFFFFFFF;
    }

    /** Per-instance overlay coord. Default: hurt/death → red, else none. Override for mob-specific
     *  flashes (e.g. creeper priming white). */
    protected int overlayCoord(T entity, float partialTick) {
        return OverlayTexture.forEntity(entity);
    }

    protected static float lerpAngle(float prev, float now, float partialTick) {
        float f = now - prev;
        while (f < -180.0F) {
            f += 360.0F;
        }
        while (f >= 180.0F) {
            f -= 360.0F;
        }
        return prev + partialTick * f;
    }

    @Override
    protected void _delete() {
        instances.delete();
        if (fire != null) {
            fire.delete();
        }
        if (shadow != null) {
            shadow.delete();
        }
        for (int i = 0, n = layers.size(); i < n; i++) {
            layers.get(i).delete();
        }
    }
}
