package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.caffeinemc.mods.sodium.client.gpu.GPULimits;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;

/**
 * Opted-in meshing jobs preserve cutoff and physical alpha before the encoder can replace them with shader data.
 * Encoded positions/UVs are copied after each write using that buffer's actual stride. Workers own recordings;
 * only accepted uploads decode atlas coordinates and publish occluders on the render thread.
 */
public final class SodiumOccluderCapture {
    private static final ThreadLocal<Recording> CURRENT = new ThreadLocal<>();

    private SodiumOccluderCapture() {
    }

    public static @Nullable Recording begin(long section) {
        assert CURRENT.get() == null;
        var capture = TerrainGeometryCache.beginMeshing(section);
        if (capture == null) return null;
        var recording = new Recording(capture);
        CURRENT.set(recording);
        return recording;
    }

    public static @Nullable Recording current(Buffer buffer) {
        var recording = CURRENT.get();
        if (recording != null) recording.buffers.add(buffer);
        return recording;
    }

    public static void end(@Nullable Recording recording) {
        if (recording == null) return;
        for (var buffer : recording.buffers) buffer.lighting$recording(null);
        recording.buffers.clear();
        CURRENT.remove();
    }

    public interface Buffer {
        void lighting$translucent(boolean translucent);

        void lighting$recording(@Nullable Recording recording);
    }

    public interface Carrier {
        @Nullable Recording lighting$occluders();

        void lighting$occluders(@Nullable Recording recording);
    }

    public static final class Recording {
        private final TerrainGeometryCache.Capture capture;
        private final FloatArrayList[] positions = new FloatArrayList[4];
        private final IntArrayList[] texture = new IntArrayList[4];
        private final FloatArrayList[] alpha = new FloatArrayList[4];
        private final ArrayList<Buffer> buffers = new ArrayList<>();

        private Recording(TerrainGeometryCache.Capture capture) {
            this.capture = capture;
        }

        public void quad(long pointer, int stride, int materialBits, ChunkVertexEncoder.Vertex[] vertices) {
            int group = materialBits >>> 1 & 3;
            if (positions[group] == null) {
                positions[group] = new FloatArrayList();
                if (group != 0) {
                    texture[group] = new IntArrayList();
                    alpha[group] = new FloatArrayList();
                }
            }
            for (int v = 0; v < 4; v++) {
                long at = pointer + (long) v * stride;
                int high = MemoryUtil.memGetInt(at), low = MemoryUtil.memGetInt(at + 4);
                for (int axis = 0; axis < 3; axis++) {
                    int shift = axis * 10;
                    int coordinate = ((high >>> shift & 1023) << 10) | (low >>> shift & 1023);
                    positions[group].add(coordinate * (32.0F / CompactChunkVertex.POSITION_MAX_VALUE) - 8.0F);
                }
                if (group != 0) {
                    texture[group].add(MemoryUtil.memGetInt(at + 12));
                    alpha[group].add((vertices[v].color >>> 24) / 255.0F);
                }
            }
        }

        public TerrainGeometryCache.@Nullable Capture complete(ClientLevel level, ChunkBuildOutput build) {
            if (!capture.accepts(level)) return null;
            var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                                 .getTexture();
            double precision = 1 << GPULimits.getSubTexelPrecisionBits();
            float shrinkU = (float) (1.0 / CompactChunkVertex.TEXTURE_MAX_VALUE - 1.0 / atlas.getWidth(0) / precision);
            float shrinkV = (float) (1.0 / CompactChunkVertex.TEXTURE_MAX_VALUE - 1.0 / atlas.getHeight(0) / precision);
            var meshes = new ArrayList<OcclusionMesh>();
            for (int group = 0; group < positions.length; group++) {
                if (positions[group] == null) continue;
                float[] xyz = positions[group].toFloatArray();
                float[] uv = group == 0 ? null : new float[texture[group].size() * 2];
                if (uv != null) for (int v = 0; v < texture[group].size(); v++) {
                    int packed = texture[group].getInt(v), u = packed & 65535, w = packed >>> 16;
                    uv[v * 2] = Math.fma((u & 32768) == 0 ? -1.0F : 1.0F, shrinkU, (u & 32767) / 32768.0F);
                    uv[v * 2 + 1] = Math.fma((w & 32768) == 0 ? -1.0F : 1.0F, shrinkV, (w & 32767) / 32768.0F);
                }
                var cutout = switch (group) {
                    case 0 -> null;
                    case 1 -> TerrainGeometryDecoder.ATLAS_TINY;
                    case 2 -> TerrainGeometryDecoder.ATLAS_HALF;
                    default -> TerrainGeometryDecoder.ATLAS_ONE;
                };
                meshes.add(new OcclusionMesh(xyz, TerrainGeometryDecoder.indices(xyz.length / 3), uv,
                        group == 0 ? null : alpha[group].toFloatArray(), cutout));
            }
            capture.complete(build.section, meshes.toArray(OcclusionMesh[]::new));
            if (build.info.animatedSprites != null) capture.sprites(build.info.animatedSprites);
            return capture;
        }
    }
}
