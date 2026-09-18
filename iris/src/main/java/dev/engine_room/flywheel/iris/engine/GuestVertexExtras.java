package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.backend.engine.MeshVertexExtras;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.irisshaders.iris.vertices.NormalHelper;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Iris {@code TERRAIN} extended attributes for pooled meshes, written as Iris's {@code BufferBuilder} does for
 * block geometry: {@code mc_Entity}, {@code mc_midTexCoord}, {@code at_tangent} (per quad), {@code at_midBlock}.
 * Baked block meshes sit at block position zero; other meshes carry no block (id -1, zero mid-block).
 */
public final class GuestVertexExtras implements MeshVertexExtras {
    public static final VertexFormat FORMAT = VertexFormat.builder(0)
                                                          .addAttribute("IrisEntity", GpuFormat.RG16_SINT)
                                                          .addAttribute("MidTexCoord", GpuFormat.RG32_FLOAT)
                                                          .addAttribute("Tangent", GpuFormat.RGBA8_SNORM)
                                                          .addAttribute("MidBlock", GpuFormat.RGBA8_SNORM)
                                                          .build();

    private final Vector3f normal = new Vector3f();
    private @Nullable Object2IntMap<BlockState> blockIds;

    /**
     * Whether Iris's block id map changed since the last {@link #write}: pooled ids are stale.
     */
    boolean blockIdsChanged() {
        return WorldRenderingSettings.INSTANCE.getBlockStateIds() != blockIds;
    }

    @Override
    public VertexFormat format() {
        return FORMAT;
    }

    @Override
    public void write(Mesh mesh, VertexList vertices, long ptr) {
        blockIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        BlockState state = mesh instanceof BakedMesh baked ? baked.blockState() : null;
        short blockId = (short) (state == null || blockIds == null ? -1 : blockIds.applyAsInt(state));
        byte emission = (byte) (state == null ? 0 : state.getLightEmission());
        int vertexCount = mesh.vertexCount();

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            long p = ptr + (long) vertex * 20L;
            MemoryUtil.memPutShort(p, blockId);
            MemoryUtil.memPutShort(p + 2L, ExtendedDataHelper.BLOCK_RENDER_TYPE);
            if (state != null) {
                MemoryUtil.memPutInt(p + 16L, ExtendedDataHelper.computeMidBlock(vertices.x(vertex),
                        vertices.y(vertex), vertices.z(vertex), 0, 0, 0));
                MemoryUtil.memPutByte(p + 19L, emission);
            } else {
                MemoryUtil.memPutInt(p + 16L, 0);
            }
        }

        int quadVertices = mesh instanceof QuadMesh ? vertexCount - vertexCount % 4 : 0;
        for (int vertex = quadVertices; vertex < vertexCount; vertex++) {
            MemoryUtil.memSet(ptr + (long) vertex * 20L + 4L, 0, 12L);
        }
        for (int quad = 0; quad < quadVertices; quad += 4) {
            float midU = 0;
            float midV = 0;
            for (int vertex = quad; vertex < quad + 4; vertex++) {
                midU += vertices.u(vertex);
                midV += vertices.v(vertex);
            }
            midU /= 4;
            midV /= 4;
            NormalHelper.computeFaceNormalManual(normal, vertices.x(quad), vertices.y(quad), vertices.z(quad),
                    vertices.x(quad + 1), vertices.y(quad + 1), vertices.z(quad + 1), vertices.x(quad + 2),
                    vertices.y(quad + 2), vertices.z(quad + 2), vertices.x(quad + 3), vertices.y(quad + 3),
                    vertices.z(quad + 3));
            int tangent = NormalHelper.computeTangent(normal.x, normal.y, normal.z, vertices.x(quad),
                    vertices.y(quad), vertices.z(quad), vertices.u(quad), vertices.v(quad), vertices.x(quad + 1),
                    vertices.y(quad + 1), vertices.z(quad + 1), vertices.u(quad + 1), vertices.v(quad + 1),
                    vertices.x(quad + 2), vertices.y(quad + 2), vertices.z(quad + 2), vertices.u(quad + 2),
                    vertices.v(quad + 2));
            for (int vertex = quad; vertex < quad + 4; vertex++) {
                long p = ptr + (long) vertex * 20L;
                MemoryUtil.memPutFloat(p + 4L, midU);
                MemoryUtil.memPutFloat(p + 8L, midV);
                MemoryUtil.memPutInt(p + 12L, tangent);
            }
        }
    }
}
