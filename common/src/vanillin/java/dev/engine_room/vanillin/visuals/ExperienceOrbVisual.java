package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.BillboardInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Instanced experience-orb visual mirroring {@code ExperienceOrbRenderer}: one BILLBOARD sprite per orb, the icon in a per-instance {@code uvRegion}.
 */
public class ExperienceOrbVisual extends AbstractEntityVisual<ExperienceOrb> implements SimpleDynamicVisual {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/experience/experience_orb.png");
    private static final float ICON = 16.0F / 64.0F;
    private static final float SIZE = 0.3F;
    private static final float LIFT = 0.1F;

    private static final RendererReloadCache<Identifier, Model> MODELS =
            new RendererReloadCache<>(tex -> new SingleMeshModel(OrbMesh.INSTANCE, material(tex)));

    private final BillboardInstance instance;
    private int currentIcon = -1;

    public ExperienceOrbVisual(VisualizationContext ctx, ExperienceOrb entity, float partialTick) {
        super(ctx, entity, partialTick);
        instance = instancerProvider().instancer(InstanceTypes.BILLBOARD, MODELS.get(TEXTURE))
                                      .createInstance();
        instance.size(SIZE);
        updateIcon();
        animate(partialTick);
    }

    private static Material material(Identifier texture) {
        return SimpleMaterial.builder()
                             .texture(texture)
                             // Flat, lightmap-only: a camera-facing sprite has no meaningful surface normal, so directional (cardinal) shading would flicker as the camera turns.
                             .light(LightShaders.FLAT)
                             .cardinalLightingMode(CardinalLightingMode.OFF)
                             // TRANSLUCENT (blend + depth write), not OIT: vanilla depth-writes orbs, so a coincident stack occludes down to one visible orb instead of additively brightening; the cutout keeps the sprite's EMPTY (alpha~0) surround from depth-writing (an unsorted instanced draw would otherwise punch invisible holes into orbs behind it).
                             .transparency(Transparency.TRANSLUCENT)
                             .cutout(CutoutShaders.ONE_TENTH)
                             .backfaceCulling(false)
                             .mipmap(false)
                             .build();
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!isVisible(context.frustum())) {
            return;
        }
        updateIcon();
        animate(context.partialTick());
    }

    private void updateIcon() {
        int icon = entity.getIcon();
        if (icon != currentIcon) {
            currentIcon = icon;
            float u0 = icon % 4 * ICON;
            float v0 = icon / 4 * ICON;
            instance.uvRegion(u0, v0, ICON, ICON);
        }
    }

    private void animate(float partialTick) {
        var origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());

        float rr = (entity.tickCount + partialTick) / 2.0F;
        int rc = (int) ((Mth.sin(rr) + 1.0F) * 0.5F * 255.0F);
        int bc = (int) ((Mth.sin(rr + (float) (Math.PI * 4.0 / 3.0)) + 1.0F) * 0.1F * 255.0F);

        instance.position(x, y + LIFT, z)
                .color(rc, 255, bc, 128)
                .light(orbLight(partialTick))
                .setChanged();
    }

    // ExperienceOrbRenderer.getBlockLightLevel: the base entity block light, boosted +7 (clamped), so orbs read brighter than their surroundings.
    private int orbLight(float partialTick) {
        BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTick));
        int blockLight = entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, pos);
        blockLight = Mth.clamp(blockLight + 7, 0, 15);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        return LightCoordsUtil.pack(blockLight, skyLight);
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    private static final class OrbMesh implements QuadMesh {
        private static final OrbMesh INSTANCE = new OrbMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 1);

        private static void writeVertex(MutableVertexList v, int i, float x, float y, float u, float w) {
            v.x(i, x);
            v.y(i, y);
            v.z(i, 0);
            v.r(i, 1);
            v.g(i, 1);
            v.b(i, 1);
            v.a(i, 1);
            v.u(i, u);
            v.v(i, w);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 1);
            v.normalZ(i, 0);
        }

        @Override
        public int vertexCount() {
            return 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            writeVertex(vertexList, 0, -0.5F, -0.25F, 0.0F, 1.0F);
            writeVertex(vertexList, 1, 0.5F, -0.25F, 1.0F, 1.0F);
            writeVertex(vertexList, 2, 0.5F, 0.75F, 1.0F, 0.0F);
            writeVertex(vertexList, 3, -0.5F, 0.75F, 0.0F, 0.0F);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
