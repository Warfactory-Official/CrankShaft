package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntitySpectralArrow;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Mirrors {@code RenderArrow}: the static back-disc + four-fin model (its constant
 *  {@code rotX(45°) · scale(0.05625) · translate(-4,0,0)} baked into the mesh) under a per-frame
 *  yaw/pitch/shake transform. Vanilla draws arrows with GL lighting disabled, so the material is
 *  unshaded and only the entity lightmap applies. */
public class ArrowVisual extends AbstractEntityVisual<EntityArrow> implements SimpleDynamicVisual {
    private static final ResourceLocation ARROW_TEXTURE = new ResourceLocation("textures/entity/projectiles/arrow.png");
    private static final ResourceLocation TIPPED_TEXTURE = new ResourceLocation("textures/entity/projectiles/tipped_arrow.png");
    private static final ResourceLocation SPECTRAL_TEXTURE = new ResourceLocation("textures/entity/projectiles/spectral_arrow.png");

    private static final Model ARROW_MODEL = model(ARROW_TEXTURE);
    private static final Model TIPPED_MODEL = model(TIPPED_TEXTURE);
    private static final Model SPECTRAL_MODEL = model(SPECTRAL_TEXTURE);

    private final Matrix4f pose = new Matrix4f();
    private TransformedInstance instance;
    private Model currentModel;

    private ArrowVisual(VisualizationContext ctx, EntityArrow entity, float partialTick, Model model) {
        super(ctx, entity, partialTick);
        currentModel = model;
        instance = createInstance(model);
        updateInstance(partialTick);
    }

    public static ArrowVisual tipped(VisualizationContext ctx, EntityTippedArrow entity, float partialTick) {
        return new ArrowVisual(ctx, entity, partialTick, tippedModel(entity));
    }

    public static ArrowVisual spectral(VisualizationContext ctx, EntitySpectralArrow entity, float partialTick) {
        return new ArrowVisual(ctx, entity, partialTick, SPECTRAL_MODEL);
    }

    private TransformedInstance createInstance(Model model) {
        TransformedInstance instance = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model)
                .createInstance();
        instance.overlay(OverlayTexture.NO_OVERLAY);
        return instance;
    }

    private static Model tippedModel(EntityTippedArrow entity) {
        return entity.getColor() > 0 ? TIPPED_MODEL : ARROW_MODEL;
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        // The tipped texture follows the potion color, which syncs after spawn.
        if (entity instanceof EntityTippedArrow tipped) {
            Model model = tippedModel(tipped);
            if (model != currentModel) {
                instance.delete();
                currentModel = model;
                instance = createInstance(model);
            }
        }
        updateInstance(ctx.partialTick());
    }

    private void updateInstance(float partialTick) {
        float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTick;
        float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTick;
        translateToInterpolatedPosition(pose, partialTick);
        pose.rotateY((float) Math.toRadians(yaw - 90.0F))
                .rotateZ((float) Math.toRadians(pitch));
        float shake = entity.arrowShake - partialTick;
        if (shake > 0.0F) {
            pose.rotateZ((float) Math.toRadians(-MathHelper.sin(shake * 3.0F) * shake));
        }
        instance.setTransform(pose)
                .light(computePackedLight(partialTick))
                .setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    private static Model model(ResourceLocation texture) {
        Material material = SimpleMaterial.builderOf(Materials.CUTOUT)
                .texture(texture)
                .cutout(CutoutShaders.ONE_TENTH)
                // RenderArrow disables GL lighting; only the entity lightmap applies.
                .cardinalLightingMode(CardinalLightingMode.OFF)
                .useOverlay(false)
                .mipmap(false)
                .build();
        return new SingleMeshModel(ArrowMesh.INSTANCE, material);
    }

    private static final class ArrowMesh implements QuadMesh {
        private static final ArrowMesh INSTANCE = new ArrowMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(-0.225F, 0, 0, 0.5F);
        private static final float SCALE = 0.05625F;

        @Override
        public int vertexCount() {
            return 6 * 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            // Vanilla's quads with rotX(45) * scale(0.05625) * translate(-4,0,0) baked in. The back
            // disc draws both windings; each fin pair shares a plane with opposite windings, standing
            // in for vanilla's cumulative rotX(90) loop.
            int i = 0;
            // Back disc, front then back winding (uv block 0..0.15625 x 0.15625..0.3125).
            i = vertex(vertexList, i, -7, -2, -2, 0.0F, 0.15625F);
            i = vertex(vertexList, i, -7, -2, 2, 0.15625F, 0.15625F);
            i = vertex(vertexList, i, -7, 2, 2, 0.15625F, 0.3125F);
            i = vertex(vertexList, i, -7, 2, -2, 0.0F, 0.3125F);
            i = vertex(vertexList, i, -7, 2, -2, 0.0F, 0.15625F);
            i = vertex(vertexList, i, -7, 2, 2, 0.15625F, 0.15625F);
            i = vertex(vertexList, i, -7, -2, 2, 0.15625F, 0.3125F);
            i = vertex(vertexList, i, -7, -2, -2, 0.0F, 0.3125F);
            // Fins: vanilla's quad (x, y, 0) under cumulative rotX(90 * (j + 1)).
            for (int j = 0; j < 4; j++) {
                i = fin(vertexList, i, j, -8, -2, 0.0F, 0.0F);
                i = fin(vertexList, i, j, 8, -2, 0.5F, 0.0F);
                i = fin(vertexList, i, j, 8, 2, 0.5F, 0.15625F);
                i = fin(vertexList, i, j, -8, 2, 0.0F, 0.15625F);
            }
        }

        private static int fin(MutableVertexList v, int i, int j, float x, float y, float u, float tv) {
            // rotX(90 * (j + 1)) applied to (x, y, 0).
            return switch (j) {
                case 0 -> vertex(v, i, x, 0, y, u, tv);
                case 1 -> vertex(v, i, x, -y, 0, u, tv);
                case 2 -> vertex(v, i, x, 0, -y, u, tv);
                default -> vertex(v, i, x, y, 0, u, tv);
            };
        }

        private static int vertex(MutableVertexList v, int i, float x, float y, float z, float u, float tv) {
            // Bake translate(-4,0,0), scale, then rotX(45).
            float sx = (x - 4.0F) * SCALE;
            float sy = y * SCALE;
            float sz = z * SCALE;
            float ry = (sy - sz) * 0.70710677F;
            float rz = (sy + sz) * 0.70710677F;
            v.x(i, sx);
            v.y(i, ry);
            v.z(i, rz);
            v.r(i, 1);
            v.g(i, 1);
            v.b(i, 1);
            v.a(i, 1);
            v.u(i, u);
            v.v(i, tv);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 1);
            v.normalZ(i, 0);
            return i + 1;
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
