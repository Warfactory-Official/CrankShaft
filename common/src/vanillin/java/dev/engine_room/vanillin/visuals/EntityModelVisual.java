package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.ConcurrentRenderStateExtraction;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Instanced visual for an entity whose vanilla renderer poses an {@link EntityModel}: the baked tree becomes one
 * {@link InstanceTree}, reposed off the vanilla render state. Capture runs in-frame on the worker when the renderer
 * {@linkplain ConcurrentRenderStateExtraction#supports extracts concurrently}, else on the render thread, applied a
 * frame later.
 */
public abstract class EntityModelVisual<T extends Entity, S extends EntityRenderState>
        extends AbstractEntityVisual<T> implements SimpleDynamicVisual {
    // Layer bakes follow resource reloads: each map rides one RendererReloadCache entry.
    private static final RendererReloadCache<Boolean, Map<ModelLayerLocation, EntityModel<?>>> SHARED_MODELS =
            new RendererReloadCache<>($ -> new ConcurrentHashMap<>());
    // Materials MUST be shared per texture -- SimpleMaterial has no equals/hashCode, so a per-visual instance would cache-miss ModelTrees.of every entity (re-baking geometry + a fresh instancer per entity).
    private static final Map<Identifier, Material> MATERIALS = new ConcurrentHashMap<>();
    private static final RendererReloadCache<Boolean, Map<ModelLayerLocation, float[]>> LAYER_REST_POSES =
            new RendererReloadCache<>($ -> new ConcurrentHashMap<>());
    private static final RendererReloadCache<Boolean, Map<ModelLayerLocation, Rigs>> RIGS =
            new RendererReloadCache<>($ -> new ConcurrentHashMap<>());
    protected final EntityRenderer<T, S> renderer;
    protected final S state;
    private final EntityModel<S>[] models;
    private final ModelLayerLocation[] layers;
    private final Rigs[] rigs;
    private final boolean concurrentCapture;
    private final PoseStack capturePose = new PoseStack();
    private final ModelPart[][] partsByVariant;
    private final String[][] namesByVariant;
    private final int[][] parentIndexByVariant;
    @Nullable
    private final ShadowComponent shadow;
    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    @Nullable
    private InstanceTree instances;
    @Nullable
    private InstanceTree[] nodesInOrder;
    private int currentVariant = -1;
    @Nullable
    private Identifier currentTexture;
    @Nullable
    private InstanceTree foilInstances;
    @Nullable
    private InstanceTree[] foilNodes;
    @Nullable
    private Material currentFoil;
    private int currentFoilVariant = -1;
    private boolean hiddenFoil;
    private volatile @Nullable Snapshot published;
    private volatile boolean deleted;
    private boolean hiddenBody;
    protected float partialTick;

    @SuppressWarnings("unchecked")
    protected EntityModelVisual(VisualizationContext ctx, T entity, float partialTick,
                                ModelLayerLocation layer, Function<ModelPart, ? extends EntityModel<S>> modelFactory) {
        this(ctx, entity, partialTick, new ModelLayerLocation[]{layer}, singleFactory(modelFactory), null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected EntityModelVisual(VisualizationContext ctx, T entity, float partialTick,
                                ModelLayerLocation[] layers,
                                Function<ModelPart, ? extends EntityModel<S>>[] modelFactories,
                                @Nullable Function<EntityRenderer<T, S>, ? extends EntityModel<S>> rendererModel) {
        super(ctx, entity, partialTick);
        if (layers.length == 0 || layers.length != modelFactories.length) {
            throw new IllegalArgumentException("model layers and factories must be non-empty and parallel");
        }
        this.layers = layers;
        this.renderer = (EntityRenderer<T, S>) Minecraft.getInstance()
                                                        .getEntityRenderDispatcher()
                                                        .getRenderer(entity);
        this.state = renderer.createRenderState();
        EntityFeatureCompat.setStateId(state, entity);
        EntityFeatureCompat.observe(entity.getType());
        this.models = new EntityModel[layers.length];
        this.rigs = new Rigs[layers.length];
        this.concurrentCapture = ConcurrentRenderStateExtraction.supports(renderer);
        this.partsByVariant = new ModelPart[layers.length][];
        this.namesByVariant = new String[layers.length][];
        this.parentIndexByVariant = new int[layers.length][];
        for (int variant = 0; variant < layers.length; variant++) {
            Function<ModelPart, ? extends EntityModel<S>> factory = modelFactories[variant];
            models[variant] = factory == null
                    ? rendererModel.apply(renderer)
                    : (EntityModel<S>) SHARED_MODELS.get(true).computeIfAbsent(layers[variant],
                    l -> factory.apply(Minecraft.getInstance().getEntityModels().bakeLayer(l)));
            EntityModel<S> prototype = models[variant];
            rigs[variant] = RIGS.get(true).computeIfAbsent(layers[variant],
                    l -> new Rigs(l, prototype, factory != null ? factory : partConstructor(prototype)));

            List<ModelPart> parts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Integer> parents = new ArrayList<>();
            flattenModel(models[variant].root(), "", -1, parts, names, parents);
            partsByVariant[variant] = parts.toArray(new ModelPart[0]);
            namesByVariant[variant] = names.toArray(new String[0]);
            int[] parentIndex = new int[parents.size()];
            for (int i = 0; i < parentIndex.length; i++) {
                parentIndex[i] = parents.get(i);
            }
            parentIndexByVariant[variant] = parentIndex;
        }

        // shadowRadius() must return a constant -- it is read here before subclass fields initialize.
        float radius = shadowRadius();
        this.shadow = radius > 0.0F ? new ShadowComponent(ctx, entity).radius(radius) : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <S extends EntityRenderState> Function<ModelPart, ? extends EntityModel<S>>[] singleFactory(
            Function<ModelPart, ? extends EntityModel<S>> factory) {
        return new Function[]{factory};
    }

    static Material materialFor(Identifier texture) {
        return MATERIALS.computeIfAbsent(texture, tex -> SimpleMaterial.builder()
                                                                       .cutout(CutoutShaders.ONE_TENTH)
                                                                       .backfaceCulling(false)
                                                                       // Vanilla never mipmaps entity textures; SimpleMaterial's mipmap-on default reads black at grazing LODs (end-crystal glass edges near-parallel to the view).
                                                                       .mipmap(false)
                                                                       .texture(tex)
                                                                       .build());
    }

    static EntityModel<?> sharedModel(ModelLayerLocation layer, Function<ModelPart, ? extends EntityModel<?>> factory) {
        return SHARED_MODELS.get(true).computeIfAbsent(layer, l -> factory.apply(Minecraft.getInstance()
                                                                                .getEntityModels()
                                                                                .bakeLayer(l)));
    }

    static Rigs rigs(ModelLayerLocation layer, Function<ModelPart, ? extends EntityModel<?>> factory) {
        return RIGS.get(true).computeIfAbsent(layer, l -> new Rigs(l, sharedModel(l, factory), factory));
    }

    // A renderer-owned model: rebuilt through its class's (ModelPart) constructor.
    private static Function<ModelPart, ? extends EntityModel<?>> partConstructor(EntityModel<?> prototype) {
        MethodHandle constructor;
        try {
            constructor = MethodHandles.publicLookup()
                                       .findConstructor(prototype.getClass(),
                                               MethodType.methodType(void.class, ModelPart.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No public (ModelPart) constructor on " + prototype.getClass(), e);
        }
        return root -> {
            try {
                return (EntityModel<?>) constructor.invoke(root);
            } catch (Throwable t) {
                throw new IllegalStateException("Could not rebuild " + prototype.getClass(), t);
            }
        };
    }

    /**
     * Capture-side copies of one layer's vanilla model, one per thread: {@code setupAnim} writes into the model, and
     * vanilla poses its own instances on the render thread. Cube-less: capture reads transforms only.
     */
    static final class Rigs {
        // The layer's bake (models may root below it).
        private final ModelPart template;
        // The prototype's visibility: model constructors set some, renderer constructors override them.
        private final ModelPart visibility;
        private final ThreadLocal<Rig> perThread;

        Rigs(ModelLayerLocation layer, EntityModel<?> prototype, Function<ModelPart, ? extends EntityModel<?>> factory) {
            template = skeleton(Minecraft.getInstance()
                                         .getEntityModels()
                                         .bakeLayer(layer));
            visibility = factory.apply(skeleton(template))
                                .root();
            copyVisibility(prototype.root(), visibility);
            perThread = ThreadLocal.withInitial(() -> {
                EntityModel<?> model = factory.apply(skeleton(template));
                copyVisibility(visibility, model.root());
                List<ModelPart> parts = new ArrayList<>();
                flattenModel(model.root(), "", -1, parts, new ArrayList<>(), new ArrayList<>());
                return new Rig(model, parts.toArray(new ModelPart[0]));
            });
        }

        Rig get() {
            return perThread.get();
        }

        private static void copyVisibility(ModelPart from, ModelPart to) {
            to.visible = from.visible;
            to.skipDraw = from.skipDraw;
            from.children.forEach((name, child) -> copyVisibility(child, to.getChild(name)));
        }

        private static ModelPart skeleton(ModelPart source) {
            Map<String, ModelPart> children = new LinkedHashMap<>();
            source.children.forEach((name, child) -> children.put(name, skeleton(child)));
            ModelPart copy = new ModelPart(List.of(), children);
            copy.setInitialPose(source.getInitialPose());
            copy.loadPose(source.getInitialPose());
            copy.visible = source.visible;
            copy.skipDraw = source.skipDraw;
            return copy;
        }
    }

    record Rig(EntityModel<?> model, ModelPart[] parts) {
    }

    // Sorted-child DFS over the vanilla model, matching InstanceTree (which sorts child names), so transforms captured by index apply to the matching instanced bone.
    static void flattenModel(ModelPart part, String name, int parent, List<ModelPart> parts,
                             List<String> names, List<Integer> parents) {
        int index = parts.size();
        parts.add(part);
        names.add(name);
        parents.add(parent);
        String[] childNames = part.children.keySet()
                                           .toArray(String[]::new);
        Arrays.sort(childNames);
        for (String childName : childNames) {
            flattenModel(part.children.get(childName), childName, index, parts, names, parents);
        }
    }

    private static void pairNodes(ModelPart part, @Nullable InstanceTree node, List<InstanceTree> nodes) {
        nodes.add(node);
        String[] childNames = part.children.keySet()
                                           .toArray(String[]::new);
        Arrays.sort(childNames);
        for (String childName : childNames) {
            InstanceTree child = node == null ? null : node.child(childName);
            pairNodes(part.children.get(childName), child, nodes);
        }
    }

    private static boolean sameChildren(InstanceTree node, ModelPart part) {
        if (node.childCount() != part.children.size()) {
            return false;
        }
        for (String name : part.children.keySet()) {
            if (!node.hasChild(name)) {
                return false;
            }
        }
        return true;
    }

    protected static float[] restPoses(ModelPart[] parts) {
        float[] rest = new float[parts.length * 9];
        for (int i = 0; i < parts.length; i++) {
            var p = parts[i].getInitialPose();
            int b = i * 9;
            rest[b] = p.x();
            rest[b + 1] = p.y();
            rest[b + 2] = p.z();
            rest[b + 3] = p.xRot();
            rest[b + 4] = p.yRot();
            rest[b + 5] = p.zRot();
            rest[b + 6] = p.xScale();
            rest[b + 7] = p.yScale();
            rest[b + 8] = p.zScale();
        }
        return rest;
    }

    static float[] layerRestPoses(ModelLayerLocation layer) {
        return LAYER_REST_POSES.get(true).computeIfAbsent(layer, l -> {
            ModelPart root = Minecraft.getInstance()
                                      .getEntityModels()
                                      .bakeLayer(l);
            List<ModelPart> parts = new ArrayList<>();
            flattenModel(root, "", -1, parts, new ArrayList<>(), new ArrayList<>());
            return restPoses(parts.toArray(new ModelPart[0]));
        });
    }

    protected abstract Identifier texture(S state);

    /**
     * Reproduce the vanilla renderer's {@code submit} prologue on the render thread, minus the world-position translate (folded in on the worker so it tracks the current render origin).
     */
    protected abstract void setupRootPose(PoseStack pose, S state);

    protected int modelVariant(S state) {
        return 0;
    }

    protected int bodyColor(S state) {
        return -1;
    }

    protected Material material(Identifier texture) {
        return materialFor(texture);
    }

    protected int overlay(S state) {
        return OverlayTexture.NO_OVERLAY;
    }

    protected boolean isHidden(S state) {
        return state.isInvisible;
    }

    @Nullable
    protected Object captureExtra(S state, EntityModel<S> model, Matrix4fc local) {
        return null;
    }

    protected void applyExtra(@Nullable Object extra, int variant, float[] transforms, boolean[] draw,
                              Matrix4f root, int light, int overlay) {
    }

    protected void onBodyCreated(int variant, InstanceTree[] nodesInOrder) {
    }

    protected void hideExtra() {
    }

    /**
     * The entity's shadow radius, or {@code 0} for no shadow. Subclasses that want a shadow manage it themselves.
     */
    protected float shadowRadius() {
        return 0.0F;
    }

    @Nullable
    protected Material foilMaterial(S state) {
        return null;
    }

    // Compat with Iris: it draws a blended vanilla entity render type after its deferred passes, through the program
    // the pipeline maps to.
    static ModelTree irisRouted(ModelTree tree, RenderType renderType) {
        RenderPipeline pipeline = renderType.pipeline();
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL) {
            return PackTaggedModel.tag(tree, List.of(PackIdentity.ENTITIES_TRANSLUCENT));
        }
        if (pipeline == RenderPipelines.EYES || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE) {
            return PackTaggedModel.tag(tree, List.of(PackIdentity.SPIDER_EYES));
        }
        return tree;
    }

    protected final EntityModel<S> model(int variant) {
        return models[variant];
    }

    protected final ModelPart[] parts(int variant) {
        return partsByVariant[variant];
    }

    protected final String[] partNames(int variant) {
        return namesByVariant[variant];
    }

    protected final ModelLayerLocation layer(int variant) {
        return layers[variant];
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        partialTick = ctx.partialTick();
        boolean vanilla = EntityFeatureCompat.vanillaOwns(entity.getType());
        if (shadow != null) {
            shadow.strength(vanilla ? 0.0F : EntityFeatureCompat.shadowStrength(
                    (float) (1.0 - entity.distanceToSqr(ctx.camera().position()) / 256.0)));
            shadow.beginFrame(ctx);
        }
        if (vanilla) {
            hideBody();
            return;
        }

        if (!isVisible(ctx.frustum())) {
            hideBody();
            return;
        }

        float partialTick = ctx.partialTick();
        if (concurrentCapture || Minecraft.getInstance().isSameThread()) {
            capture(partialTick);
        } else if (capturePending.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(() -> capture(partialTick));
        }

        Snapshot snapshot = published;
        if (snapshot != null) {
            apply(snapshot);
        }
    }

    private void capture(float partialTick) {
        capturePending.set(false);
        if (deleted) {
            return;
        }

        renderer.extractRenderState(entity, state, partialTick);
        if (isHidden(state)) {
            published = Snapshot.HIDDEN;
            return;
        }

        Vec3 offset = renderer.getRenderOffset(state);
        PoseStack pose = capturePose;
        pose.setIdentity();
        setupRootPose(pose, state);
        Matrix4f local = new Matrix4f(pose.last().pose());

        int variant = Math.clamp(modelVariant(state), 0, models.length - 1);
        Rig rig = rigs[variant].get();
        @SuppressWarnings("unchecked")
        EntityModel<S> model = (EntityModel<S>) rig.model();
        ModelPart[] partsInOrder = rig.parts();
        int[] parentIndex = parentIndexByVariant[variant];
        model.setupAnim(state);
        int n = partsInOrder.length;
        float[] transforms = new float[n * 9];
        boolean[] draw = new boolean[n];
        boolean[] effVisible = new boolean[n];
        for (int i = 0; i < n; i++) {
            ModelPart p = partsInOrder[i];
            int b = i * 9;
            transforms[b] = p.x;
            transforms[b + 1] = p.y;
            transforms[b + 2] = p.z;
            transforms[b + 3] = p.xRot;
            transforms[b + 4] = p.yRot;
            transforms[b + 5] = p.zRot;
            transforms[b + 6] = p.xScale;
            transforms[b + 7] = p.yScale;
            transforms[b + 8] = p.zScale;
            // Vanilla ModelPart.render: an invisible part hides its whole subtree, skipDraw hides only its own cubes.
            boolean vis = p.visible && (parentIndex[i] < 0 || effVisible[parentIndex[i]]);
            effVisible[i] = vis;
            draw[i] = vis && !p.skipDraw;
        }

        Object extra = captureExtra(state, model, local);
        Identifier texture = texture(state);
        EntityFeatureCompat.observeTexture(entity.getType(), texture);
        published = new Snapshot(local, state.x + offset.x, state.y + offset.y, state.z + offset.z,
                transforms, draw, state.lightCoords, overlay(state), texture, variant, bodyColor(state),
                foilMaterial(state), extra);
    }

    private void apply(Snapshot snapshot) {
        if (!snapshot.visible()) {
            hideBody();
            return;
        }
        ensureBody(snapshot.variant(), snapshot.texture());
        if (hiddenBody) {
            instances.visible(true);
            hiddenBody = false;
        }

        float[] t = snapshot.transforms();
        boolean[] draw = snapshot.draw();
        for (int i = 0; i < nodesInOrder.length; i++) {
            InstanceTree node = nodesInOrder[i];
            if (node == null) {
                continue;
            }
            int b = i * 9;
            node.pos(t[b], t[b + 1], t[b + 2]);
            node.rotation(t[b + 3], t[b + 4], t[b + 5]);
            node.scale(t[b + 6], t[b + 7], t[b + 8]);
            node.skipDraw(!draw[i]);
        }

        Vec3i origin = renderOrigin();
        Matrix4f root = new Matrix4f()
                .translate((float) (snapshot.x() - origin.getX()), (float) (snapshot.y() - origin.getY()),
                        (float) (snapshot.z() - origin.getZ()))
                .mul(snapshot.local());
        instances.updateInstances(root);

        int light = snapshot.light();
        int overlay = snapshot.overlay();
        int bodyColor = snapshot.bodyColor();
        instances.traverse(instance -> {
            instance.light(light);
            instance.overlay(overlay);
            instance.colorArgb(bodyColor);
            instance.setChanged();
        });

        applyFoil(snapshot.foil(), snapshot.variant(), t, draw, root, light, overlay);
        applyExtra(snapshot.extra(), snapshot.variant(), t, draw, root, light, overlay);
    }

    private void applyFoil(@Nullable Material material, int variant, float[] t, boolean[] draw, Matrix4f root,
                           int light, int overlay) {
        if (material == null) {
            hideFoil();
            return;
        }
        if (foilInstances == null || material != currentFoil || variant != currentFoilVariant) {
            if (foilInstances != null) {
                foilInstances.delete();
            }
            currentFoil = material;
            currentFoilVariant = variant;
            foilInstances = InstanceTree.create(instancerProvider(), ModelTrees.of(layers[variant], material));
            InstanceTree base = foilInstances;
            ModelPart modelRoot = models[variant].root();
            while (!sameChildren(base, modelRoot) && base.childCount() == 1) {
                base = base.child(0);
            }
            List<InstanceTree> nodes = new ArrayList<>();
            pairNodes(modelRoot, sameChildren(base, modelRoot) ? base : null, nodes);
            foilNodes = nodes.toArray(new InstanceTree[0]);
            hiddenFoil = false;
        }
        if (hiddenFoil) {
            foilInstances.visible(true);
            hiddenFoil = false;
        }
        for (int i = 0; i < foilNodes.length; i++) {
            InstanceTree node = foilNodes[i];
            if (node == null) {
                continue;
            }
            int b = i * 9;
            node.pos(t[b], t[b + 1], t[b + 2]);
            node.rotation(t[b + 3], t[b + 4], t[b + 5]);
            node.scale(t[b + 6], t[b + 7], t[b + 8]);
            node.skipDraw(!draw[i]);
        }
        foilInstances.updateInstances(root);
        foilInstances.traverse(instance -> {
            instance.light(light);
            instance.overlay(overlay);
            instance.setChanged();
        });
    }

    private void hideFoil() {
        if (foilInstances != null && !hiddenFoil) {
            foilInstances.visible(false);
            hiddenFoil = true;
        }
    }

    private void ensureBody(int variant, Identifier texture) {
        if (instances != null && variant == currentVariant && texture.equals(currentTexture)) {
            return;
        }
        if (instances != null) {
            instances.delete();
        }
        currentVariant = variant;
        currentTexture = texture;
        instances = InstanceTree.create(instancerProvider(),
                irisRouted(ModelTrees.of(layers[variant], material(texture)), models[variant].renderType(texture)));
        // Some models nest their animated root under wrapper parts of the baked layer (super(root.getChild("root"))), so model.root() is a descendant of the bake root; descend single-child wrappers until the instance node's children match the live root, and the wrappers above stay at rest.
        InstanceTree base = instances;
        ModelPart modelRoot = models[variant].root();
        while (!sameChildren(base, modelRoot) && base.childCount() == 1) {
            base = base.child(0);
        }
        List<InstanceTree> nodes = new ArrayList<>();
        pairNodes(modelRoot, sameChildren(base, modelRoot) ? base : null, nodes);
        nodesInOrder = nodes.toArray(new InstanceTree[0]);
        hiddenBody = false;
        onBodyCreated(variant, nodesInOrder);
    }

    protected final void hideBody() {
        if (instances != null && !hiddenBody) {
            instances.visible(false);
            hiddenBody = true;
        }
        hideFoil();
        hideExtra();
    }

    @Override
    protected void _delete() {
        deleted = true;
        if (instances != null) {
            instances.delete();
        }
        if (foilInstances != null) {
            foilInstances.delete();
        }
        if (shadow != null) {
            shadow.delete();
        }
    }

    private record Snapshot(@Nullable Matrix4f local, double x, double y, double z,
                            float @Nullable [] transforms, boolean @Nullable [] draw, int light, int overlay,
                            @Nullable Identifier texture, int variant, int bodyColor,
                            @Nullable Material foil, @Nullable Object extra) {
        static final Snapshot HIDDEN = new Snapshot(null, 0, 0, 0, null, null, 0, 0, null, 0, -1, null, null);

        boolean visible() {
            return local != null;
        }
    }
}
