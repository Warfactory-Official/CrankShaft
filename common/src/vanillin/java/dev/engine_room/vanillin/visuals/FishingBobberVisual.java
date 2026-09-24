package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.instance.BillboardInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.FishingHook;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * The bobber quad of {@code FishingHookRenderer}; vanilla keeps the line. Unaffected by frustum culling like vanilla.
 */
public final class FishingBobberVisual extends AbstractEntityVisual<FishingHook> implements SimpleDynamicVisual {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/fishing/fishing_hook.png");
    private static final Material MATERIAL = SimpleMaterial.builder()
                                                           .texture(TEXTURE)
                                                           .cutout(CutoutShaders.ONE_TENTH)
                                                           .mipmap(false)
                                                           .build();
    private static final Model MODEL = new SingleMeshModel(BobberMesh.INSTANCE, MATERIAL);

    private final BillboardInstance instance;
    private boolean hidden;

    public FishingBobberVisual(VisualizationContext ctx, FishingHook entity, float partialTick) {
        super(ctx, entity, partialTick);
        EntityFeatureCompat.observeTexture(entity.getType(), TEXTURE);
        instance = instancerProvider().instancer(InstanceTypes.BILLBOARD, MODEL).createInstance();
        instance.size(0.5F);
        animate(partialTick);
    }

    public static boolean isSupported(FishingHook hook) {
        return hook.getPlayerOwner() != null && !EntityFeatureCompat.vanillaOwns(hook.getType());
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        animate(ctx.partialTick());
    }

    private void animate(float partialTick) {
        if (!isSupported(entity)) {
            if (!hidden) {
                instance.setVisible(false);
                hidden = true;
            }
            return;
        }
        if (hidden) {
            instance.setVisible(true);
            hidden = false;
        }
        var origin = renderOrigin();
        instance.position((float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX()),
                        (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY()),
                        (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ()))
                .light(computePackedLight(partialTick))
                .setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    private static final class BobberMesh implements QuadMesh {
        private static final BobberMesh INSTANCE = new BobberMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 0.71F);

        // FishingHookRenderer.vertex: (x - 0.5, y - 0.5, 0), normal +Y.
        private static void vertex(MutableVertexList v, int i, float x, float y, float u, float w) {
            v.x(i, x - 0.5F);
            v.y(i, y - 0.5F);
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
        public void write(MutableVertexList v) {
            vertex(v, 0, 0, 0, 0, 1);
            vertex(v, 1, 1, 0, 1, 1);
            vertex(v, 2, 1, 1, 1, 0);
            vertex(v, 3, 0, 1, 0, 0);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
