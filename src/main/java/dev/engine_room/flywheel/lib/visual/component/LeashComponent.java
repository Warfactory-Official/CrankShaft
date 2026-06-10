package dev.engine_room.flywheel.lib.visual.component;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.LeashInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.LevelRenderer;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Instanced {@code RenderLiving.renderLeash}: one instance per leashed mob, with the rope curve
 *  evaluated in the vertex shader from the two endpoints. The owner creates this while a leash
 *  holder exists and deletes it when the leash drops or the visual hides (hidden entities fall
 *  back to vanilla, which draws its own rope). */
public final class LeashComponent {
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation("flywheel", "textures/flywheel/white.png");
    // Vanilla draws the rope with GL lighting and unit-0 texturing disabled but the entity's
    // lightmap still bound: plain vertex colors modulated by entity light only.
    private static final Material MATERIAL = SimpleMaterial.builder()
            .texture(WHITE_TEXTURE)
            .mipmap(false)
            .backfaceCulling(false)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useOverlay(false)
            .build();
    private static final Model MODEL = new SingleMeshModel(LeashMesh.INSTANCE, MATERIAL);

    private final VisualizationContext context;
    private final EntityLiving entity;
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final LeashInstance instance;

    public LeashComponent(VisualizationContext context, EntityLiving entity) {
        this.context = context;
        this.entity = entity;
        instance = context.instancerProvider().instancer(InstanceTypes.LEASH, MODEL)
                .createInstance();
        instance.scale(1.0F);
    }

    public void beginFrame(float partialTick) {
        Entity holder = entity.getLeashHolder();
        // Vanilla interpolates the holder's view angles at half speed.
        double yaw = Math.toRadians(interpolate(holder.prevRotationYaw, holder.rotationYaw, partialTick * 0.5F));
        double pitch = Math.toRadians(interpolate(holder.prevRotationPitch, holder.rotationPitch, partialTick * 0.5F));
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double sinPitch = Math.sin(pitch);
        if (holder instanceof EntityHanging) {
            // Leash knots have no swinging hand; vanilla pins the offset straight down.
            cosYaw = 0.0;
            sinYaw = 0.0;
            sinPitch = -1.0;
        }
        double cosPitch = Math.cos(pitch);
        double handX = interpolate(holder.prevPosX, holder.posX, partialTick) - cosYaw * 0.7 - sinYaw * 0.5 * cosPitch;
        double handY = interpolate(holder.prevPosY, holder.posY, partialTick) + holder.getEyeHeight() * 0.7 - sinPitch * 0.5 - 0.25;
        double handZ = interpolate(holder.prevPosZ, holder.posZ, partialTick) - sinYaw * 0.7 + cosYaw * 0.5 * cosPitch;

        double attachYaw = Math.toRadians(interpolate(entity.prevRenderYawOffset, entity.renderYawOffset, partialTick)) + Math.PI / 2.0;
        double attachX = Math.cos(attachYaw) * entity.width * 0.4;
        double attachZ = Math.sin(attachYaw) * entity.width * 0.4;
        double baseX = interpolate(entity.prevPosX, entity.posX, partialTick);
        double baseZ = interpolate(entity.prevPosZ, entity.posZ, partialTick);
        double entityX = baseX + attachX;
        double entityY = interpolate(entity.prevPosY, entity.posY, partialTick);
        double entityZ = baseZ + attachZ;

        var origin = context.renderOrigin();
        // Vanilla lowers the rendered start by (1.6 - height) * 0.5 but measures the rope delta
        // from the unadjusted entity Y.
        instance.endpoints(
                (float) (entityX - origin.getX()),
                (float) (entityY - (1.6 - entity.height) * 0.5 - origin.getY()),
                (float) (entityZ - origin.getZ()),
                (float) (handX - entityX),
                (float) (handY - entityY),
                (float) (handZ - entityZ));
        // Light samples the body eye position (vanilla's rope inherits the entity lightmap); the
        // attach offset belongs only in the geometry.
        scratchPos.setPos(MathHelper.floor(baseX),
                MathHelper.floor(entityY + entity.getEyeHeight()),
                MathHelper.floor(baseZ));
        instance.light(LevelRenderer.getEntityLight(entity, scratchPos));
        instance.setChanged();
    }

    public void delete() {
        instance.delete();
    }

    private static double interpolate(double prev, double cur, float partialTick) {
        return prev + (cur - prev) * partialTick;
    }

    private static final class LeashMesh implements QuadMesh {
        private static final LeashMesh INSTANCE = new LeashMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 1);
        private static final int SEGMENTS = 24;

        @Override
        public int vertexCount() {
            return SEGMENTS * 2 * 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            // Vanilla's two 24-segment triangle strips as quads. UV.x carries the segment fraction;
            // even segment indices are shaded 0.7x. The strip's cross-segment color gradient becomes
            // per-quad bilinear interpolation — visually identical alternation.
            int i = 0;
            for (int ribbon = 0; ribbon < 2; ribbon++) {
                for (int j = 0; j < SEGMENTS; j++) {
                    writeVertex(vertexList, i++, ribbon, false, j);
                    writeVertex(vertexList, i++, ribbon, true, j);
                    writeVertex(vertexList, i++, ribbon, true, j + 1);
                    writeVertex(vertexList, i++, ribbon, false, j + 1);
                }
            }
        }

        private static void writeVertex(MutableVertexList v, int i, int ribbon, boolean side, int j) {
            float shade = j % 2 == 0 ? 0.7F : 1.0F;
            float ox;
            float oy;
            float oz;
            if (ribbon == 0) {
                ox = side ? 0.025F : 0.0F;
                oy = side ? 0.025F : 0.0F;
                oz = 0.0F;
            } else {
                ox = side ? 0.025F : 0.0F;
                oy = side ? 0.0F : 0.025F;
                oz = side ? 0.025F : 0.0F;
            }
            v.x(i, ox);
            v.y(i, oy);
            v.z(i, oz);
            v.r(i, 0.5F * shade);
            v.g(i, 0.4F * shade);
            v.b(i, 0.3F * shade);
            v.a(i, 1.0F);
            v.u(i, j / (float) SEGMENTS);
            v.v(i, 0);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 1);
            v.normalZ(i, 0);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
