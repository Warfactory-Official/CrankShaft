package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;

import java.nio.ByteOrder;
import java.util.ArrayList;

public final class TerrainGeometryDecoder {
    public static final OcclusionMesh.Cutout ATLAS_TINY = OcclusionMesh.Cutout.blockAtlas(0.0001F);
    public static final OcclusionMesh.Cutout ATLAS_HALF = OcclusionMesh.Cutout.blockAtlas(0.5F);
    public static final OcclusionMesh.Cutout ATLAS_ONE = OcclusionMesh.Cutout.blockAtlas(1);

    private TerrainGeometryDecoder() {
    }

    public static OcclusionMesh[] vanilla(SectionCompiler.Results results) {
        var out = new ArrayList<OcclusionMesh>();
        for (var layer : ChunkSectionLayer.values()) {
            if (layer == ChunkSectionLayer.TRANSLUCENT) continue;
            var mesh = results.renderedLayers.get(layer);
            if (mesh == null) continue;
            var format = mesh.drawState().format();
            int stride = format.getVertexSize();
            int count = mesh.drawState().vertexCount();
            if (count == 0) continue;
            assert count % 4 == 0;
            int position = format.getElement("Position").offset();
            int color = format.getElement("Color").offset();
            int texture = format.getElement("UV0").offset();
            var data = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int first = data.position();
            float[] xyz = new float[count * 3];
            float[] uv = layer == ChunkSectionLayer.CUTOUT ? new float[count * 2] : null;
            float[] alpha = uv == null ? null : new float[count];
            for (int i = 0; i < count; i++) {
                int at = first + i * stride;
                for (int axis = 0; axis < 3; axis++) xyz[i * 3 + axis] = data.getFloat(at + position + axis * 4);
                if (uv != null) {
                    uv[i * 2] = data.getFloat(at + texture);
                    uv[i * 2 + 1] = data.getFloat(at + texture + 4);
                    alpha[i] = (data.getInt(at + color) >>> 24) / 255.0F;
                }
            }
            out.add(new OcclusionMesh(xyz, indices(count), uv, alpha, uv == null ? null : ATLAS_HALF));
        }
        return out.toArray(OcclusionMesh[]::new);
    }

    static int[] indices(int vertices) {
        int[] indices = new int[vertices / 4 * 6];
        for (int v = 0, at = 0; v < vertices; v += 4) {
            indices[at++] = v;
            indices[at++] = v + 1;
            indices[at++] = v + 2;
            indices[at++] = v + 2;
            indices[at++] = v + 3;
            indices[at++] = v;
        }
        return indices;
    }
}
