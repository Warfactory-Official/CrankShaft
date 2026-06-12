package dev.engine_room.flywheel.lib.visual.component;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.GlyphInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.SimpleMaterialShaders;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class NameTagComponent implements EntityComponent {
    private static final MaterialShaders GRAYSCALE_SHADERS = new SimpleMaterialShaders(
            ResourceUtil.rl("material/default.vert"), ResourceUtil.rl("material/nametag.frag"));

    private static final RendererReloadCache<Identifier, Model> FONT_SEE_THROUGH_MODELS =
            new RendererReloadCache<>(id -> new SingleMeshModel(GlyphMesh.INSTANCE,
                    fontMaterial(id, DepthTest.OFF, WriteMask.COLOR, true)));
    private static final RendererReloadCache<Identifier, Model> FONT_SOLID_MODELS =
            new RendererReloadCache<>(id -> new SingleMeshModel(GlyphMesh.INSTANCE,
                    fontMaterial(id, DepthTest.LEQUAL, WriteMask.COLOR_DEPTH, true)));
    private static final RendererReloadCache<Identifier, Model> FONT_COLOR_SEE_THROUGH_MODELS =
            new RendererReloadCache<>(id -> new SingleMeshModel(GlyphMesh.INSTANCE,
                    fontMaterial(id, DepthTest.OFF, WriteMask.COLOR, false)));
    private static final RendererReloadCache<Identifier, Model> FONT_COLOR_SOLID_MODELS =
            new RendererReloadCache<>(id -> new SingleMeshModel(GlyphMesh.INSTANCE,
                    fontMaterial(id, DepthTest.LEQUAL, WriteMask.COLOR_DEPTH, false)));

    private static final Matrix4f IDENTITY = new Matrix4f();
    // 26.2 submitNameTag's see-through text color is 0x80FFFFFF: half-alpha, not 1.21.1's 0x20.
    private static final int SEE_THROUGH_ALPHA = 0x80;
    private static final int SOLID_ALPHA = 0xFF;

    private static final int BIAS_BACKGROUND = 0;
    private static final int BIAS_TEXT_SEE_THROUGH = 1;
    private static final int BIAS_TEXT_SOLID = 2;

    private final VisualizationContext context;
    private final Entity entity;
    private final Level level;
    private final Font font = Minecraft.getInstance().font;
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final GlyphCapture capture = new GlyphCapture();
    private final ArrayList<GlyphInstance> instances = new ArrayList<>();
    private final ArrayList<GlyphInstance> emissiveInstances = new ArrayList<>();

    // Font access runs only on the render thread (26.2 bakes sheets lazily; the caches aren't thread-safe): the
    // worker requests a capture via Minecraft.execute and consumes the published snapshot a frame later.
    private @Nullable LayoutKey requestedKey;
    private volatile @Nullable CapturedLayout capturedLayout;
    private @Nullable CapturedLayout appliedLayout;

    private Supplier<@Nullable Component> nameTag;
    private BooleanSupplier shouldShow;

    public NameTagComponent(VisualizationContext context, Entity entity) {
        this.context = context;
        this.entity = entity;
        this.level = entity.level();
        nameTag = entity::getDisplayName;
        shouldShow = () -> entity.shouldShowName()
                || entity.hasCustomName() && entity == Minecraft.getInstance()
                        .getEntityRenderDispatcher().crosshairPickEntity;
    }

    private static Model seeThroughModel(CapturedQuad quad) {
        return (quad.grayscale ? FONT_SEE_THROUGH_MODELS : FONT_COLOR_SEE_THROUGH_MODELS).get(quad.atlas);
    }

    private static Model solidModel(CapturedQuad quad) {
        return (quad.grayscale ? FONT_SOLID_MODELS : FONT_COLOR_SOLID_MODELS).get(quad.atlas);
    }

    private static boolean isGrayscale(GpuTextureView view) {
        return view.texture()
                   .getFormat() == GpuFormat.R8_UNORM;
    }

    private static Material fontMaterial(Identifier atlas, DepthTest depthTest, WriteMask writeMask,
                                         boolean grayscale) {
        var builder = SimpleMaterial.builder()
                                    .texture(atlas)
                                    .light(LightShaders.FLAT)
                                    .cutout(CutoutShaders.ONE_TENTH)
                                    .transparency(Transparency.TRANSLUCENT)
                                    .depthTest(depthTest)
                                    .writeMask(writeMask)
                                    .cardinalLightingMode(CardinalLightingMode.OFF)
                                    .useOverlay(false)
                                    .mipmap(false);
        if (grayscale) {
            builder.shaders(GRAYSCALE_SHADERS);
        }
        return builder.build();
    }

    public NameTagComponent nameTag(Supplier<@Nullable Component> nameTag) {
        this.nameTag = nameTag;
        return this;
    }

    public NameTagComponent shouldShow(BooleanSupplier shouldShow) {
        this.shouldShow = shouldShow;
        return this;
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        float partialTick = ctx.partialTick();
        Component name = nameTag.get();
        // extractNameTags' gate: within 64 blocks && shouldShowName; no invisibility check (vanilla shows tags on invisible entities).
        if (name == null || entity.distanceToSqr(ctx.camera()
                                                    .position()) >= 4096.0 || !shouldShow.getAsBoolean()) {
            clearInstances();
            appliedLayout = null;
            return;
        }

        boolean notSneaking = !entity.isDiscrete();
        int background = ARGB.color(Minecraft.getInstance().options.getBackgroundOpacity(0.25f), 0xFF000000);
        LayoutKey key = new LayoutKey(name, notSneaking, background);
        if (!key.equals(requestedKey)) {
            requestedKey = key;
            Minecraft.getInstance().execute(() -> capturedLayout = captureLayout(key));
        }
        CapturedLayout layout = capturedLayout;
        // Only emit a snapshot matching the LATEST request; a stale in-flight capture is skipped, not shown.
        if (layout != null && layout != appliedLayout && layout.key().equals(requestedKey)) {
            appliedLayout = layout;
            emitInstances(layout);
        }
        // §k scramble: re-capture every frame while the snapshot carries obfuscated glyphs (one frame latent, indistinguishable for random noise).
        if (layout != null && layout.obfuscated() && layout.key().equals(key)) {
            Minecraft.getInstance().execute(() -> capturedLayout = captureLayout(key));
        }

        Vec3 anchor = anchor(partialTick);
        int light = computePackedLight(partialTick);
        int emissiveLight = LightCoordsUtil.lightCoordsWithEmission(light, 2);
        float ax = (float) anchor.x;
        float ay = (float) anchor.y;
        float az = (float) anchor.z;
        for (GlyphInstance glyph : instances) {
            glyph.anchor(ax, ay, az)
                 .light(light)
                 .setChanged();
        }
        for (GlyphInstance glyph : emissiveInstances) {
            glyph.anchor(ax, ay, az)
                 .light(emissiveLight)
                 .setChanged();
        }
    }

    private int computePackedLight(float partialTick) {
        scratchPos.set(BlockPos.containing(entity.getLightProbePosition(partialTick)));
        int blockLight = entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, scratchPos);
        int skyLight = level.getBrightness(LightLayer.SKY, scratchPos);
        return LightCoordsUtil.pack(blockLight, skyLight);
    }

    private CapturedLayout captureLayout(LayoutKey key) {
        var quads = new ArrayList<CapturedQuad>();
        var text = key.name()
                      .getVisualOrderText();
        int width = font.width(text);
        float half = width / 2.0f;

        boolean[] obfuscated = {false};
        text.accept((position, style, codepoint) -> {
            if (style.isObfuscated() && codepoint != 32) {
                obfuscated[0] = true;
                return false;
            }
            return true;
        });

        // visit() emits the background effect FIRST (iff its alpha != 0), then glyphs, then text effects.
        boolean[] backgroundPending = {ARGB.alpha(key.background()) != 0};
        font.prepareText(text, -half, 0.0f, 0xFFFFFFFF, false, false, key.background())
            .visit(new Font.GlyphVisitor() {
                @Override
                public void acceptGlyph(TextRenderable.Styled glyph) {
                    captureGlyph(glyph, quads);
                }

                @Override
                public void acceptEffect(TextRenderable effect) {
                    boolean background = backgroundPending[0];
                    backgroundPending[0] = false;
                    captureEffect(effect, background, quads);
                }
            });
        return new CapturedLayout(key, List.copyOf(quads), obfuscated[0]);
    }

    private void emitInstances(CapturedLayout layout) {
        clearInstances();
        boolean notSneaking = layout.key()
                                    .notSneaking();

        for (CapturedQuad quad : layout.quads()) {
            // submitNameTag's split: see-through half-alpha + depth-tested full-alpha lit with emission (sneaking:
            // depth-tested half-alpha only). The background renders once with its own captured colour.
            if (quad.background) {
                emitQuad(notSneaking ? seeThroughModel(quad) : solidModel(quad), quad, ARGB.alpha(quad.argb),
                        BIAS_BACKGROUND, false);
            } else if (notSneaking) {
                emitQuad(seeThroughModel(quad), quad, SEE_THROUGH_ALPHA, BIAS_TEXT_SEE_THROUGH, false);
                emitQuad(solidModel(quad), quad, SOLID_ALPHA, BIAS_TEXT_SOLID, true);
            } else {
                emitQuad(solidModel(quad), quad, SEE_THROUGH_ALPHA, BIAS_TEXT_SOLID, false);
            }
        }
    }

    private void captureGlyph(TextRenderable.Styled glyph, ArrayList<CapturedQuad> out) {
        GpuTextureView view = glyph.textureView();
        Identifier atlas = GlyphAtlases.lookup(view);
        if (atlas == null) {
            return;
        }

        capture.reset();
        glyph.render(IDENTITY, capture, LightCoordsUtil.FULL_BRIGHT, false);
        boolean grayscale = isGrayscale(view);

        // BakedSheetGlyph.render emits top-left..bottom-right corners (bold: a second offset quad); italics shear
        // top/bottom apart in x, so the GLYPH shader re-applies a symmetric shear -- never bake it into offset/size.
        int quadCount = Math.min(capture.count, GlyphCapture.CAPACITY) / 4;
        for (int q = 0; q < quadCount; q++) {
            int i = q * 4;
            float topLeftX = capture.x[i];
            float bottomLeftX = capture.x[i + 1];
            float topY = capture.y[i];
            float bottomY = capture.y[i + 1];
            float offsetX = (topLeftX + bottomLeftX) * 0.5f;
            float sizeX = capture.x[i + 3] - topLeftX;
            float sizeY = bottomY - topY;
            float shear = (topLeftX - bottomLeftX) * 0.5f;
            float u0 = capture.u[i];
            float v0 = capture.v[i];
            float uw = capture.u[i + 2] - u0;
            float vh = capture.v[i + 2] - v0;
            out.add(new CapturedQuad(offsetX, topY, sizeX, sizeY, shear, u0, v0, uw, vh,
                    capture.z[i], capture.color[i], atlas, grayscale, false));
        }
    }

    private void captureEffect(TextRenderable effect, boolean background, ArrayList<CapturedQuad> out) {
        GpuTextureView view = effect.textureView();
        Identifier atlas = GlyphAtlases.lookup(view);
        if (atlas == null) {
            return;
        }

        capture.reset();
        effect.render(IDENTITY, capture, LightCoordsUtil.FULL_BRIGHT, false);
        if (capture.count < 4) {
            return;
        }

        float x0 = Math.min(Math.min(capture.x[0], capture.x[1]), Math.min(capture.x[2], capture.x[3]));
        float x1 = Math.max(Math.max(capture.x[0], capture.x[1]), Math.max(capture.x[2], capture.x[3]));
        float y0 = Math.min(Math.min(capture.y[0], capture.y[1]), Math.min(capture.y[2], capture.y[3]));
        float y1 = Math.max(Math.max(capture.y[0], capture.y[1]), Math.max(capture.y[2], capture.y[3]));
        float u0 = Math.min(Math.min(capture.u[0], capture.u[1]), Math.min(capture.u[2], capture.u[3]));
        float u1 = Math.max(Math.max(capture.u[0], capture.u[1]), Math.max(capture.u[2], capture.u[3]));
        float v0 = Math.min(Math.min(capture.v[0], capture.v[1]), Math.min(capture.v[2], capture.v[3]));
        float v1 = Math.max(Math.max(capture.v[0], capture.v[1]), Math.max(capture.v[2], capture.v[3]));
        out.add(new CapturedQuad(x0, y0, x1 - x0, y1 - y0, 0.0f, u0, v0, u1 - u0, v1 - v0,
                capture.z[0], capture.color[0], atlas, isGrayscale(view), background));
    }

    private void emitQuad(Model model, CapturedQuad quad, int alpha, int bias, boolean emissive) {
        GlyphInstance instance = newGlyph(model, bias, emissive);
        instance.placement(quad.offsetX, quad.offsetY, quad.sizeX, quad.sizeY, quad.shear);
        instance.uvRegion(quad.u0, quad.v0, quad.uw, quad.vh);
        instance.color(ARGB.red(quad.argb), ARGB.green(quad.argb), ARGB.blue(quad.argb), alpha);
        instance.depth(quad.z);
    }

    private GlyphInstance newGlyph(Model model, int bias, boolean emissive) {
        GlyphInstance instance = context.instancerProvider()
                                        .instancer(InstanceTypes.GLYPH, model, bias)
                                        .createInstance();
        (emissive ? emissiveInstances : instances).add(instance);
        return instance;
    }

    private Vec3 anchor(float partialTick) {
        Vec3 pos = entity.getPosition(partialTick);
        Vec3 attach = entity.getAttachments()
                            .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
        double offX = attach != null ? attach.x : 0.0;
        double offY = (attach != null ? attach.y : entity.getBbHeight()) + 0.5;
        double offZ = attach != null ? attach.z : 0.0;
        Vec3i origin = context.renderOrigin();
        return new Vec3(pos.x + offX - origin.getX(), pos.y + offY - origin.getY(), pos.z + offZ - origin.getZ());
    }

    private void clearInstances() {
        for (GlyphInstance glyph : instances) {
            glyph.delete();
        }
        instances.clear();
        for (GlyphInstance glyph : emissiveInstances) {
            glyph.delete();
        }
        emissiveInstances.clear();
    }

    @Override
    public void delete() {
        clearInstances();
        appliedLayout = null;
        requestedKey = null;
    }

    private record CapturedQuad(float offsetX, float offsetY, float sizeX, float sizeY, float shear,
                                float u0, float v0, float uw, float vh, float z, int argb,
                                Identifier atlas, boolean grayscale, boolean background) {
    }

    private record LayoutKey(Component name, boolean notSneaking, int background) {
    }

    private record CapturedLayout(LayoutKey key, List<CapturedQuad> quads, boolean obfuscated) {
    }

    private static final class GlyphCapture implements VertexConsumer {
        static final int CAPACITY = 16;

        private final float[] x = new float[CAPACITY];
        private final float[] y = new float[CAPACITY];
        private final float[] z = new float[CAPACITY];
        private final float[] u = new float[CAPACITY];
        private final float[] v = new float[CAPACITY];
        private final int[] color = new int[CAPACITY];
        private int count;

        void reset() {
            count = 0;
        }

        @Override
        public VertexConsumer addVertex(float px, float py, float pz) {
            if (count < x.length) {
                x[count] = px;
                y[count] = py;
                z[count] = pz;
            }
            count++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            store(color, ARGB.color(a, r, g, b));
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            store(color, argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float pu, float pv) {
            int i = count - 1;
            if (i >= 0 && i < u.length) {
                u[i] = pu;
                v[i] = pv;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int pu, int pv) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int pu, int pv) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        private void store(int[] arr, int value) {
            int i = count - 1;
            if (i >= 0 && i < arr.length) {
                arr[i] = value;
            }
        }
    }

    private static final class GlyphMesh implements QuadMesh {
        private static final GlyphMesh INSTANCE = new GlyphMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 1);

        private static void writeVertex(MutableVertexList v, int i, float x, float y) {
            v.x(i, x);
            v.y(i, y);
            v.z(i, 0);
            v.r(i, 1);
            v.g(i, 1);
            v.b(i, 1);
            v.a(i, 1);
            v.u(i, x);
            v.v(i, y);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 0);
            v.normalZ(i, 1);
        }

        @Override
        public int vertexCount() {
            return 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            writeVertex(vertexList, 0, 0, 0);
            writeVertex(vertexList, 1, 0, 1);
            writeVertex(vertexList, 2, 1, 1);
            writeVertex(vertexList, 3, 1, 0);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
