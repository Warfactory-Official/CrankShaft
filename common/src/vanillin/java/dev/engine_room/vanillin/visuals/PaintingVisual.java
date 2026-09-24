package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * {@code PaintingRenderer} as two instances per 1x1 cell, each lit by its cell's block: the front art sub-rectangle
 * and the back with the outer-border strips that cell owns.
 */
public final class PaintingVisual extends AbstractEntityVisual<Painting> implements SimpleDynamicVisual {
    private static final Identifier BACK_SPRITE = Identifier.withDefaultNamespace("back");
    // Entity Texture Features' key for the back sprite.
    private static final Identifier BACK_TEXTURE = Identifier.withDefaultNamespace("textures/painting/back.png");
    private static final float EDGE = 0.03125F;
    private static final float STRIP = 0.0625F;
    private static final int TOP = 1;
    private static final int BOTTOM = 2;
    private static final int MAX_X = 4;
    private static final int MIN_X = 8;
    // 26.2: vanilla's forward view-offset layering (view-space x 4097/4096) is omitted; no coplanar geometry.
    private static final Material MATERIAL = SimpleMaterial.builder()
                                                           .texture(Sheets.PAINTINGS_SHEET)
                                                           .mipmap(false)
                                                           .build();
    private static final Model FRONT = new SingleMeshModel(new CellMesh(-1), MATERIAL);
    private static final Model[] BACKS = new Model[16];

    static {
        for (int sides = 0; sides < BACKS.length; sides++) {
            BACKS[sides] = new SingleMeshModel(new CellMesh(sides), MATERIAL);
        }
    }

    private UvTransformedInstance[] fronts = new UvTransformedInstance[0];
    private UvTransformedInstance[] backs = new UvTransformedInstance[0];
    private int[] lights = new int[0];
    @Nullable
    private PaintingVariant variant;
    @Nullable
    private Direction direction;

    public PaintingVisual(VisualizationContext ctx, Painting entity, float partialTick) {
        super(ctx, entity, partialTick);
        EntityFeatureCompat.observe(entity.getType());
        EntityFeatureCompat.observeTexture(entity.getType(), BACK_TEXTURE);
        update();
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (EntityFeatureCompat.vanillaOwns(entity.getType())) {
            deleteInstances();
            return;
        }
        if (isVisible(ctx.frustum())) {
            update();
        }
    }

    private void update() {
        PaintingVariant current = entity.getVariant().value();
        Direction facing = entity.getDirection();
        if (current != variant || facing != direction) {
            rebuild(current, facing);
        }
        updateLight();
    }

    private void rebuild(PaintingVariant current, Direction facing) {
        deleteInstances();
        variant = current;
        direction = facing;
        Identifier asset = current.assetId();
        EntityFeatureCompat.observeTexture(entity.getType(), asset.withPath(path -> "textures/painting/" + path + ".png"));
        int width = current.width();
        int height = current.height();
        fronts = new UvTransformedInstance[width * height];
        backs = new UvTransformedInstance[width * height];
        lights = new int[width * height];

        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PAINTINGS);
        TextureAtlasSprite front = atlas.getSprite(current.assetId());
        TextureAtlasSprite back = atlas.getSprite(BACK_SPRITE);
        float du = 1.0F / width;
        float dv = 1.0F / height;

        var origin = renderOrigin();
        Vec3 pos = entity.position();
        Matrix4f root = new Matrix4f().translation((float) (pos.x - origin.getX()), (float) (pos.y - origin.getY()),
                                                   (float) (pos.z - origin.getZ()))
                                      .rotateY((180 - facing.get2DDataValue() * 90) * Mth.DEG_TO_RAD);
        Matrix4f cell = new Matrix4f();
        for (int segmentY = 0; segmentY < height; segmentY++) {
            for (int segmentX = 0; segmentX < width; segmentX++) {
                int i = segmentX + segmentY * width;
                cell.set(root).translate(segmentX - width / 2.0F, segmentY - height / 2.0F, 0.0F);

                float u0 = front.getU(du * (width - segmentX));
                float u1 = front.getU(du * (width - (segmentX + 1)));
                float v0 = front.getV(dv * (height - segmentY));
                float v1 = front.getV(dv * (height - (segmentY + 1)));
                fronts[i] = instancerProvider().instancer(InstanceTypes.UV_TRANSFORMED, FRONT).createInstance();
                fronts[i].uvRegion(u0, v0, u1 - u0, v1 - v0).setTransform(cell);

                int sides = (segmentY == height - 1 ? TOP : 0) | (segmentY == 0 ? BOTTOM : 0)
                        | (segmentX == width - 1 ? MAX_X : 0) | (segmentX == 0 ? MIN_X : 0);
                backs[i] = instancerProvider().instancer(InstanceTypes.UV_TRANSFORMED, BACKS[sides]).createInstance();
                backs[i].uvRegion(back.getU0(), back.getV0(), back.getU1() - back.getU0(), back.getV1() - back.getV0())
                        .setTransform(cell);
                lights[i] = -1;
            }
        }
    }

    // PaintingRenderer.extractRenderState's per-segment block.
    private void updateLight() {
        int width = variant.width();
        int height = variant.height();
        float offsetX = -width / 2.0F;
        float offsetY = -height / 2.0F;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int segmentY = 0; segmentY < height; segmentY++) {
            for (int segmentX = 0; segmentX < width; segmentX++) {
                float segmentOffsetX = segmentX + offsetX + 0.5F;
                float segmentOffsetY = segmentY + offsetY + 0.5F;
                int x = entity.getBlockX();
                int y = Mth.floor(entity.getY() + segmentOffsetY);
                int z = entity.getBlockZ();
                switch (direction) {
                    case NORTH -> x = Mth.floor(entity.getX() + segmentOffsetX);
                    case WEST -> z = Mth.floor(entity.getZ() - segmentOffsetX);
                    case SOUTH -> x = Mth.floor(entity.getX() - segmentOffsetX);
                    case EAST -> z = Mth.floor(entity.getZ() + segmentOffsetX);
                    default -> {
                    }
                }
                int i = segmentX + segmentY * width;
                int light = LightCoordsUtil.getLightCoords(level, pos.set(x, y, z));
                if (light != lights[i]) {
                    lights[i] = light;
                    fronts[i].light(light).setChanged();
                    backs[i].light(light).setChanged();
                }
            }
        }
    }

    private void deleteInstances() {
        for (UvTransformedInstance instance : fronts) {
            instance.delete();
        }
        for (UvTransformedInstance instance : backs) {
            instance.delete();
        }
        fronts = new UvTransformedInstance[0];
        backs = new UvTransformedInstance[0];
    }

    @Override
    protected void _delete() {
        deleteInstances();
    }

    /**
     * One unit cell, x/y in [0, 1] (vanilla x1/y1 = 0, x0/y0 = 1), vertices in {@code renderPainting}'s order. UVs are
     * fractions of the instance's {@code uvRegion}: the front's own cell rectangle, else the back sprite.
     */
    private static final class CellMesh implements QuadMesh {
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0.5F, 0.5F, 0.0F, 0.71F);
        // sides < 0: the front quad.
        private final int sides;

        private CellMesh(int sides) {
            this.sides = sides;
        }

        @Override
        public int vertexCount() {
            return sides < 0 ? 4 : 4 * (1 + Integer.bitCount(sides));
        }

        @Override
        public void write(MutableVertexList v) {
            if (sides < 0) {
                vertex(v, 0, 1, 0, -EDGE, 1, 0, 0, 0, -1);
                vertex(v, 1, 0, 0, -EDGE, 0, 0, 0, 0, -1);
                vertex(v, 2, 0, 1, -EDGE, 0, 1, 0, 0, -1);
                vertex(v, 3, 1, 1, -EDGE, 1, 1, 0, 0, -1);
                return;
            }
            vertex(v, 0, 1, 1, EDGE, 1, 0, 0, 0, 1);
            vertex(v, 1, 0, 1, EDGE, 0, 0, 0, 0, 1);
            vertex(v, 2, 0, 0, EDGE, 0, 1, 0, 0, 1);
            vertex(v, 3, 1, 0, EDGE, 1, 1, 0, 0, 1);
            int i = 4;
            if ((sides & TOP) != 0) {
                vertex(v, i++, 1, 1, -EDGE, 0, 0, 0, 1, 0);
                vertex(v, i++, 0, 1, -EDGE, 1, 0, 0, 1, 0);
                vertex(v, i++, 0, 1, EDGE, 1, STRIP, 0, 1, 0);
                vertex(v, i++, 1, 1, EDGE, 0, STRIP, 0, 1, 0);
            }
            if ((sides & BOTTOM) != 0) {
                vertex(v, i++, 1, 0, EDGE, 0, 0, 0, -1, 0);
                vertex(v, i++, 0, 0, EDGE, 1, 0, 0, -1, 0);
                vertex(v, i++, 0, 0, -EDGE, 1, STRIP, 0, -1, 0);
                vertex(v, i++, 1, 0, -EDGE, 0, STRIP, 0, -1, 0);
            }
            if ((sides & MAX_X) != 0) {
                vertex(v, i++, 1, 1, EDGE, STRIP, 0, -1, 0, 0);
                vertex(v, i++, 1, 0, EDGE, STRIP, 1, -1, 0, 0);
                vertex(v, i++, 1, 0, -EDGE, 0, 1, -1, 0, 0);
                vertex(v, i++, 1, 1, -EDGE, 0, 0, -1, 0, 0);
            }
            if ((sides & MIN_X) != 0) {
                vertex(v, i++, 0, 1, -EDGE, STRIP, 0, 1, 0, 0);
                vertex(v, i++, 0, 0, -EDGE, STRIP, 1, 1, 0, 0);
                vertex(v, i++, 0, 0, EDGE, 0, 1, 1, 0, 0);
                vertex(v, i, 0, 1, EDGE, 0, 0, 1, 0, 0);
            }
        }

        private static void vertex(MutableVertexList v, int i, float x, float y, float z, float u, float w, float nx,
                                   float ny, float nz) {
            v.x(i, x);
            v.y(i, y);
            v.z(i, z);
            v.r(i, 1);
            v.g(i, 1);
            v.b(i, 1);
            v.a(i, 1);
            v.u(i, u);
            v.v(i, w);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, nx);
            v.normalY(i, ny);
            v.normalZ(i, nz);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
