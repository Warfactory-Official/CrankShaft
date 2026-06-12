package dev.engine_room.flywheel.lib.visual.component;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.LeashInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Instanced {@code LeashFeatureRenderer}: one {@link InstanceTypes#LEASH LEASH} instance per leashed entity.
 */
public final class LeashComponent implements EntityComponent {
    private static final Material MATERIAL = SimpleMaterial.builder()
                                                           .texture(ResourceUtil.rl("textures/flywheel/white.png"))
                                                           .mipmap(false)
                                                           .backfaceCulling(false)
                                                           .cardinalLightingMode(CardinalLightingMode.OFF)
                                                           .useOverlay(false)
                                                           .build();
    private static final Model MODEL = new SingleMeshModel(LeashMesh.INSTANCE, MATERIAL);

    private final VisualizationContext context;
    private final Entity entity;
    private final Leashable leashable;
    private final Level level;
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final LeashInstance instance;

    public LeashComponent(VisualizationContext context, Entity entity) {
        this.context = context;
        this.entity = entity;
        this.leashable = (Leashable) entity;
        this.level = entity.level();
        this.instance = context.instancerProvider()
                               .instancer(InstanceTypes.LEASH, MODEL)
                               .createInstance();
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        Entity holder = leashable.getLeashHolder();
        if (holder == null) {
            instance.setVisible(false);
            return;
        }

        float partialTick = ctx.partialTick();
        float entityYRot = entity.getPreciseBodyRotation(partialTick) * (float) (Math.PI / 180.0);
        Vec3 attachOffset = leashable.getLeashOffset(partialTick)
                                     .yRot(-entityYRot);
        Vec3 start = entity.getPosition(partialTick)
                           .add(attachOffset);
        Vec3 end = holder.getRopeHoldPosition(partialTick);

        // Reveal BEFORE writing: a hidden handle's slabPtr() is the write-only trash slot, so setters
        // are dropped while hidden and the reveal re-seeds the fresh slot (scale 0 = a degenerate rope).
        // Scale is rewritten every frame, else an unleash/re-leash cycle leaves the rope collapsed.
        instance.setVisible(true);
        Vec3i origin = context.renderOrigin();
        instance.endpoints(
                (float) (start.x - origin.getX()),
                (float) (start.y - origin.getY()),
                (float) (start.z - origin.getZ()),
                (float) (end.x - start.x),
                (float) (end.y - start.y),
                (float) (end.z - start.z));
        instance.scale(1.0f);
        instance.light(computePackedLight(partialTick));
        instance.setChanged();
    }

    // Mirrors AbstractEntityVisual.computePackedLight: entity-sensitive light (light-probe position + on-fire override).
    private int computePackedLight(float partialTick) {
        scratchPos.set(BlockPos.containing(entity.getLightProbePosition(partialTick)));
        int blockLight = entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, scratchPos);
        int skyLight = level.getBrightness(LightLayer.SKY, scratchPos);
        return LightCoordsUtil.pack(blockLight, skyLight);
    }

    @Override
    public void delete() {
        instance.delete();
    }

    /**
     * Vanilla's two 24-step leash ribbons; UV.x carries the rope-curve step fraction the shader evaluates at.
     */
    private static final class LeashMesh implements QuadMesh {
        private static final LeashMesh INSTANCE = new LeashMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 1);
        private static final int STEPS = 24;

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
            v.u(i, j / (float) STEPS);
            v.v(i, 0);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 1);
            v.normalZ(i, 0);
        }

        @Override
        public int vertexCount() {
            return STEPS * 2 * 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            int i = 0;
            for (int ribbon = 0; ribbon < 2; ribbon++) {
                for (int j = 0; j < STEPS; j++) {
                    writeVertex(vertexList, i++, ribbon, false, j);
                    writeVertex(vertexList, i++, ribbon, true, j);
                    writeVertex(vertexList, i++, ribbon, true, j + 1);
                    writeVertex(vertexList, i++, ribbon, false, j + 1);
                }
            }
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
