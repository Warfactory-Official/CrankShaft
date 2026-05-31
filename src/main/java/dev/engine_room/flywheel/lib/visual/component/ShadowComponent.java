package dev.engine_room.flywheel.lib.visual.component;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.ShadowInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * A component that uses instances to render an entity's shadow.
 *
 * <p>Use {@link #radius(float)} to set the radius of the shadow, in blocks.
 * <br>
 * Use {@link #strength(float)} to set the strength of the shadow.
 * <br>
 * The shadow will be cast on blocks at most {@code min(radius, 2 * strength)} blocks below the entity.</p>
 */
public final class ShadowComponent implements EntityComponent {
    private static final ResourceLocation SHADOW_TEXTURE = new ResourceLocation("textures/misc/shadow.png");
    private static final Material SHADOW_MATERIAL = SimpleMaterial.builder()
            .texture(SHADOW_TEXTURE)
            .mipmap(false)
            .polygonOffset(true)
            .transparency(Transparency.TRANSLUCENT)
            .writeMask(WriteMask.COLOR)
            .build();
    private static final Model SHADOW_MODEL = new SingleMeshModel(ShadowMesh.INSTANCE, SHADOW_MATERIAL);

    private final VisualizationContext context;
    private final Entity entity;
    private final World level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final InstanceRecycler<ShadowInstance> instances = new InstanceRecycler<>(this::createInstance);

    private float radius = 0;
    private float strength = 1.0F;

    public ShadowComponent(VisualizationContext context, Entity entity) {
        this.context = context;
        this.entity = entity;
        this.level = entity.world;
    }

    private ShadowInstance createInstance() {
        return context.instancerProvider()
                .instancer(InstanceTypes.SHADOW, SHADOW_MODEL)
                .createInstance();
    }

    public float radius() {
        return radius;
    }

    public float strength() {
        return strength;
    }

    /**
     * Set the radius of the shadow, in blocks, clamped to a maximum of 32.
     *
     * <p>Setting this to {@code <= 0} will disable the shadow.</p>
     *
     * @param radius The radius of the shadow, in blocks.
     */
    public ShadowComponent radius(float radius) {
        this.radius = Math.min(radius, 32);
        return this;
    }

    /**
     * Set the strength of the shadow.
     *
     * @param strength The strength of the shadow.
     */
    public ShadowComponent strength(float strength) {
        this.strength = strength;
        return this;
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        instances.resetCount();

        if (Minecraft.getMinecraft().gameSettings.entityShadows && radius > 0 && !entity.isInvisible()) {
            setupInstances(context);
        }

        instances.discardExtra();
    }

    private void setupInstances(DynamicVisual.Context ctx) {
        float partialTick = ctx.partialTick();
        double entityX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick;
        double entityY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick;
        double entityZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick;
        // Fade from the render-view entity (player) feet, matching vanilla RenderManager.getDistanceToCamera
        // (viewerPos) — NOT the rendered camera, which differs in third-person/freecam. 256 = 16² ⇒ fades to
        // nothing by 16 blocks, as vanilla.
        Entity viewer = ctx.camera().getEntity();
        double camX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTick;
        double camY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTick;
        double camZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTick;
        double dx = entityX - camX;
        double dy = entityY - camY;
        double dz = entityZ - camZ;
        float distanceFade = (float) (1.0 - (dx * dx + dy * dy + dz * dz) / 256.0);
        if (distanceFade <= 0.0F) {
            return;
        }
        float fadedStrength = strength * distanceFade;
        float castDistance = Math.min(strength * 2, radius);
        int minXPos = MathHelper.floor(entityX - radius);
        int maxXPos = MathHelper.floor(entityX + radius);
        int minYPos = MathHelper.floor(entityY - castDistance);
        int maxYPos = MathHelper.floor(entityY);
        int minZPos = MathHelper.floor(entityZ - radius);
        int maxZPos = MathHelper.floor(entityZ + radius);

        for (int z = minZPos; z <= maxZPos; ++z) {
            for (int x = minXPos; x <= maxXPos; ++x) {
                pos.setPos(x, 0, z);
                Chunk chunk = level.getChunk(pos);

                for (int y = minYPos; y <= maxYPos; ++y) {
                    pos.setPos(x, y, z);
                    float strengthGivenYFalloff = fadedStrength - (float) (entityY - pos.getY()) * 0.5F;
                    setupInstance(chunk, entityX, entityZ, strengthGivenYFalloff);
                }
            }
        }
    }

    private void setupInstance(Chunk chunk, double entityX, double entityZ, float strength) {
        int rawBrightness = level.getLight(pos);
        if (rawBrightness <= 3) return;

        float blockBrightness = level.provider.getLightBrightnessTable()[rawBrightness];
        float alpha = strength * 0.5F * blockBrightness;
        if (alpha < 0.0F) return;
        if (alpha > 1.0F) alpha = 1.0F;

        pos.setPos(pos.getX(), pos.getY() - 1, pos.getZ());
        AxisAlignedBB shape = getShapeAt(chunk, pos);
        if (shape == null) {
            pos.setPos(pos.getX(), pos.getY() + 1, pos.getZ());
            return;
        }

        var renderOrigin = context.renderOrigin();
        int x = pos.getX() - renderOrigin.getX();
        int y = pos.getY() - renderOrigin.getY() + 1;  // +1 since we moved pos down.
        int z = pos.getZ() - renderOrigin.getZ();
        pos.setPos(pos.getX(), pos.getY() + 1, pos.getZ());  // restore

        double minX = x + shape.minX;
        double minY = y + shape.minY;
        double minZ = z + shape.minZ;
        double maxX = x + shape.maxX;
        double maxZ = z + shape.maxZ;

        instances.get().write(
                (float) minX, (float) minY, (float) minZ,
                // Subtract the origin in double before casting (like the body's getVisualPosition); casting
                // the full world coord first drifts the shadow off the entity by pixels at large coordinates.
                (float) (entityX - renderOrigin.getX()), (float) (entityZ - renderOrigin.getZ()),
                (float) (maxX - minX), (float) (maxZ - minZ),
                alpha, this.radius
        ).setChanged();
    }

    @Nullable
    private AxisAlignedBB getShapeAt(Chunk chunk, BlockPos pos) {
        IBlockState state = chunk.getBlockState(pos);
        if (state.getRenderType() == EnumBlockRenderType.INVISIBLE) return null;
        if (!state.isFullCube()) return null;
        AxisAlignedBB bb = state.getBoundingBox(level, pos);
        if (bb == null) return null;
        return bb;
    }

    @Override
    public void delete() {
        instances.delete();
    }

    private static final class ShadowMesh implements QuadMesh {
        private static final float SQRT_2_OVER_2 = (float) (Math.sqrt(2) * 0.5);
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0.5f, 0, 0.5f, SQRT_2_OVER_2);
        private static final ShadowMesh INSTANCE = new ShadowMesh();

        private ShadowMesh() {
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

        private static void writeVertex(MutableVertexList vertexList, int i, float x, float z) {
            vertexList.x(i, x);
            vertexList.y(i, 0);
            vertexList.z(i, z);
            vertexList.r(i, 1);
            vertexList.g(i, 1);
            vertexList.b(i, 1);
            vertexList.a(i, 1);
            vertexList.u(i, 0);
            vertexList.v(i, 0);
            vertexList.light(i, LightTexture.FULL_BRIGHT);
            vertexList.overlay(i, OverlayTexture.NO_OVERLAY);
            vertexList.normalX(i, 0);
            vertexList.normalY(i, 1);
            vertexList.normalZ(i, 0);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
