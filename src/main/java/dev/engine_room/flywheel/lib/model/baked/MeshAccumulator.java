package dev.engine_room.flywheel.lib.model.baked;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.math.DataPacker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.LitQuadMesh;
import dev.engine_room.flywheel.lib.model.MidUvMesh;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * 1.12.2: collapsed upstream's {@code MeshEmitter} / {@code MeshEmitterManager} /
 * {@code ByteBufferBuilderStack} / {@code MeshHelper} trio (all 1.16+-internal types:
 * {@code ByteBufferBuilder}, {@code MeshData}, {@code RenderType}-keyed pools) into a single
 * per-{@link Material} accumulator that writes {@link FullVertexView}
 * bytes directly. Public API ({@link BakedModelBuilder} / {@link BlockModelBuilder} /
 * {@code BlockMaterialFunction}) is unchanged.
 */
final class MeshAccumulator {
    private static final long INITIAL_CAPACITY_VERTS = 16;

    // Shader-pack toggles reuse baked models, so keep their sprite centers while AUSM is installed.
    private static final boolean BAKE_MID_UV = Loader.isModLoaded("ausm") || FMLClientHandler.instance().hasOptifine();

    // Reference equality on Material: matches upstream's expectation that callers reuse
    // canonical instances (e.g. Materials.SOLID_BLOCK, or a single .build() per call site).
    private final Reference2ReferenceLinkedOpenHashMap<Material, Bucket> buckets = new Reference2ReferenceLinkedOpenHashMap<>();

    private final Vector3f scratchE1 = new Vector3f();
    private final Vector3f scratchE2 = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();
    private final Vector4f scratchPos = new Vector4f();
    @Nullable
    private IBlockState currentState;
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    private float centerZ = 0.5f;

    private static int clamp4bit(float lightmapFloat) {
        int v = Math.round(lightmapFloat * 65535f / 32f);
        if (v < 0) return 0;
        if (v > 15) return 15;
        return v;
    }

    void block(@Nullable IBlockState state, @Nullable Matrix4f pose) {
        currentState = state;
        scratchPos.set(0.5f, 0.5f, 0.5f, 1);
        if (pose != null) scratchPos.mul(pose);
        centerX = scratchPos.x;
        centerY = scratchPos.y;
        centerZ = scratchPos.z;
    }

    /**
     * Append a fully-lit quad: position/color/uv/lightmap arrived already shaded + AO-multiplied
     * from the Forge lighter pipeline. Normal is computed from the four positions via cross
     * product (the lighter's internal normal isn't forwarded to BLOCK-format parents).
     *
     * @param positions    {@code float[4][3]} in 0..1 model-local space (with sub-block shift applied)
     * @param colors       {@code float[4][4]} RGBA in 0..1 — caller's tint, AO multiplier, and diffuse all baked in
     * @param uvs          {@code float[4][2]} atlas coords in 0..1
     * @param lightmaps    {@code float[4][2]} (block, sky) in 0..1 (lightmap-texcoord / 65535 form)
     * @param pose         optional Matrix4f applied to positions
     * @param normalMatrix optional Matrix3f applied to the computed normal (must be {@code pose.normal(...)})
     */
    void appendLitQuad(Material material,
                       float[][] positions, float[][] colors, float[][] uvs, float[][] lightmaps,
                       @Nullable Matrix4f pose, @Nullable Matrix3f normalMatrix) {
        Bucket bucket = buckets.computeIfAbsent(material, m -> new Bucket());
        bucket.ensure(4);
        if (bucket.states != null) {
            bucket.states.add(currentState);
            bucket.centres.add(centerX);
            bucket.centres.add(centerY);
            bucket.centres.add(centerZ);
        }

        // (p2-p0) × (p3-p1) for the outward normal. Forge's VertexLighterFlat uses the inverse
        // (chunk renderer reads quad.getFace() for brightness, not the computed normal — wrong
        // sign there is invisible). Flywheel's diffuse(normal) does read it, so wrong sign flips
        // top/bottom face brightness.
        scratchE1.set(positions[3][0] - positions[1][0], positions[3][1] - positions[1][1],
                positions[3][2] - positions[1][2]);
        scratchE2.set(positions[2][0] - positions[0][0], positions[2][1] - positions[0][1],
                positions[2][2] - positions[0][2]);
        scratchE2.cross(scratchE1, scratchNormal);
        if (scratchNormal.lengthSquared() > 0F) scratchNormal.normalize();
        if (normalMatrix != null) {
            scratchNormal.mul(normalMatrix);
            if (scratchNormal.lengthSquared() > 0F) scratchNormal.normalize();
        }
        byte nx = DataPacker.packNormI8(scratchNormal.x);
        byte ny = DataPacker.packNormI8(scratchNormal.y);
        byte nz = DataPacker.packNormI8(scratchNormal.z);

        long basePtr = bucket.buffer.ptr();
        long writeVertex = bucket.vertexCount;

        // Opposite-corner UVs preserve sprite centers even when a baked quad rotates its texture.
        float midU = 0f, midV = 0f;
        if (bucket.midUvBuffer != null) {
            midU = (uvs[0][0] + uvs[2][0]) * 0.5f;
            midV = (uvs[0][1] + uvs[2][1]) * 0.5f;
        }

        for (int v = 0; v < 4; v++) {
            long dst = basePtr + (writeVertex + v) * FullVertexView.STRIDE;

            float px = positions[v][0], py = positions[v][1], pz = positions[v][2];
            if (pose != null) {
                scratchPos.set(px, py, pz, 1.0f).mul(pose);
                px = scratchPos.x;
                py = scratchPos.y;
                pz = scratchPos.z;
            }
            MemoryUtil.memPutFloat(dst, px);
            MemoryUtil.memPutFloat(dst + 4, py);
            MemoryUtil.memPutFloat(dst + 8, pz);

            int cr = Math.min(255, (int) (colors[v][0] * 255f));
            int cg = Math.min(255, (int) (colors[v][1] * 255f));
            int cb = Math.min(255, (int) (colors[v][2] * 255f));
            int ca = Math.min(255, (int) (colors[v][3] * 255f));
            MemoryUtil.memPutInt(dst + 12, cr | (cg << 8) | (cb << 16) | (ca << 24));

            MemoryUtil.memPutFloat(dst + 16, uvs[v][0]);
            MemoryUtil.memPutFloat(dst + 20, uvs[v][1]);

            MemoryUtil.memPutInt(dst + 24, OverlayTexture.NO_OVERLAY);

            // lightmap floats are (4-bit-value * 32) / 65535; recover via * (65535/32).
            int block4 = clamp4bit(lightmaps[v][0]);
            int sky4 = clamp4bit(lightmaps[v][1]);
            MemoryUtil.memPutInt(dst + 28, (sky4 << 20) | (block4 << 4));

            MemoryUtil.memPutInt(dst + 32, (nx & 0xFF) | ((ny & 0xFF) << 8) | ((nz & 0xFF) << 16));

            if (bucket.midUvBuffer != null) {
                long mDst = bucket.midUvBuffer.ptr() + (writeVertex + v) * MidUvMesh.MID_UV_BYTES_PER_VERTEX;
                MemoryUtil.memPutFloat(mDst, midU);
                MemoryUtil.memPutFloat(mDst + 4, midV);
            }
        }

        bucket.vertexCount += 4;
    }

    ImmutableList<Model.ConfiguredMesh> build(String descriptorPrefix) {
        ImmutableList.Builder<Model.ConfiguredMesh> out = ImmutableList.builder();
        for (var e : buckets.reference2ReferenceEntrySet()) {
            Bucket b = e.getValue();
            if (b.vertexCount == 0) {
                b.buffer.free();
                if (b.midUvBuffer != null) b.midUvBuffer.free();
                continue;
            }
            long finalBytes = (long) b.vertexCount * FullVertexView.STRIDE;
            MemoryBlock trimmed = b.buffer.size() == finalBytes ? b.buffer : b.buffer.realloc(finalBytes);
            FullVertexView vv = new FullVertexView();
            vv.nativeMemoryOwner(trimmed);
            vv.ptr(trimmed.ptr());
            vv.vertexCount(b.vertexCount);
            String descriptor = descriptorPrefix + ",material=" + e.getKey();
            if (b.midUvBuffer != null) {
                long midFinalBytes = (long) b.vertexCount * MidUvMesh.MID_UV_BYTES_PER_VERTEX;
                MemoryBlock midTrimmed = b.midUvBuffer.size() == midFinalBytes
                        ? b.midUvBuffer
                        : b.midUvBuffer.realloc(midFinalBytes);
                out.add(new Model.ConfiguredMesh(e.getKey(),
                        new LitQuadMesh(vv, descriptor, midTrimmed, b.states.toArray(IBlockState[]::new),
                                b.centres.toFloatArray())));
            } else {
                out.add(new Model.ConfiguredMesh(e.getKey(),
                        new SimpleQuadMesh(vv, descriptor)));
            }
        }
        buckets.clear();
        return out.build();
    }

    private static final class Bucket {
        @Nullable
        final ObjectArrayList<IBlockState> states = BAKE_MID_UV ? new ObjectArrayList<>() : null;
        @Nullable
        final FloatArrayList centres = BAKE_MID_UV ? new FloatArrayList() : null;
        MemoryBlock buffer;
        @Nullable MemoryBlock midUvBuffer;
        int vertexCount;

        Bucket() {
            buffer = MemoryBlock.mallocTracked(INITIAL_CAPACITY_VERTS * FullVertexView.STRIDE);
            midUvBuffer = BAKE_MID_UV
                    ? MemoryBlock.mallocTracked(INITIAL_CAPACITY_VERTS * MidUvMesh.MID_UV_BYTES_PER_VERTEX)
                    : null;
            vertexCount = 0;
        }

        void ensure(int additionalVertices) {
            long needed = (long) (vertexCount + additionalVertices) * FullVertexView.STRIDE;
            if (needed > buffer.size()) {
                long newSize = Math.max(needed, buffer.size() * 2);
                buffer = buffer.realloc(newSize);
            }
            if (midUvBuffer != null) {
                long midNeeded = (long) (vertexCount + additionalVertices) * MidUvMesh.MID_UV_BYTES_PER_VERTEX;
                if (midNeeded > midUvBuffer.size()) {
                    long newSize = Math.max(midNeeded, midUvBuffer.size() * 2);
                    midUvBuffer = midUvBuffer.realloc(newSize);
                }
            }
        }
    }
}
