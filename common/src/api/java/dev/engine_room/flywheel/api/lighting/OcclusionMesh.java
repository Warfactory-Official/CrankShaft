package dev.engine_room.flywheel.api.lighting;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Immutable two-sided triangle surfaces for ambient visibility on either backend. Positions are
 * model-local XYZ triples and indices preserve the rendered mesh's triangles. This object owns copies
 * of its inputs and may be shared across visuals and threads. Optional UVs and alpha coverage represent
 * cutout holes. It does not register or draw geometry, or imply translucent light transmission.
 */
public final class OcclusionMesh {
    private final float[] positions;
    private final int[] indices;
    private final float @Nullable [] uv;
    private final float @Nullable [] alpha;
    private final @Nullable Cutout cutout;

    public OcclusionMesh(float[] positions, int[] indices) {
        this(positions, indices, null, null);
    }

    public OcclusionMesh(float[] positions, int[] indices, float @Nullable [] uv, @Nullable Cutout cutout) {
        this(positions, indices, uv, null, cutout);
    }

    public OcclusionMesh(float[] positions, int[] indices, float @Nullable [] uv, float @Nullable [] alpha,
                         @Nullable Cutout cutout) {
        if (positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0)
            throw new IllegalArgumentException();
        for (float position : positions) if (!Float.isFinite(position)) throw new IllegalArgumentException();
        int vertices = positions.length / 3;
        for (int index : indices) if (index < 0 || index >= vertices) throw new IndexOutOfBoundsException(index);
        if ((uv == null) != (cutout == null) || uv != null && uv.length != vertices * 2)
            throw new IllegalArgumentException();
        if (uv != null)
            for (float coordinate : uv) if (!Float.isFinite(coordinate)) throw new IllegalArgumentException();
        if (alpha != null) {
            if (cutout == null || alpha.length != vertices) throw new IllegalArgumentException();
            for (float value : alpha)
                if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException();
        }
        this.positions = positions.clone();
        this.indices = indices.clone();
        this.uv = uv == null ? null : uv.clone();
        this.alpha = alpha == null ? null : alpha.clone();
        this.cutout = cutout;
    }

    private OcclusionMesh(OcclusionMesh source, Cutout cutout) {
        positions = source.positions;
        indices = source.indices;
        uv = source.uv;
        alpha = source.alpha;
        this.cutout = cutout;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public float coordinate(int vertex, int axis) {
        return positions[vertex * 3 + axis];
    }

    public int index(int triangle, int corner) {
        return indices[triangle * 3 + corner];
    }

    public float uv(int vertex, int axis) {
        return uv[vertex * 2 + axis];
    }

    public float alpha(int vertex) {
        return alpha == null ? 1 : alpha[vertex];
    }

    public @Nullable Cutout cutout() {
        return cutout;
    }

    /**
     * Rebinds texture coverage without copying immutable triangle or vertex data.
     */
    public OcclusionMesh withCutout(Cutout cutout) {
        if (uv == null) throw new IllegalStateException();
        return new OcclusionMesh(this, cutout);
    }

    public boolean sameGeometry(OcclusionMesh other) {
        return cutout == other.cutout && Arrays.equals(positions, other.positions) && Arrays.equals(indices,
                other.indices)
                && Arrays.equals(uv, other.uv) && Arrays.equals(alpha, other.alpha);
    }

    /**
     * Immutable row-major alpha texels. Coverage uses repeat addressing and base-level sampling,
     * bilinear by default or nearest when requested, independent of camera mip selection. Values below
     * threshold are holes, not translucent occluders. Live atlas coverage uses nearest atlas addressing.
     */
    public static final class Cutout {
        private final int width, height;
        private final byte[] alpha;
        private final float threshold;
        private final boolean nearest;

        public Cutout(int width, int height, byte[] alpha, float threshold) {
            this(width, height, alpha, threshold, false);
        }

        public Cutout(int width, int height, byte[] alpha, float threshold, boolean nearest) {
            if (width <= 0 || height <= 0 || alpha.length != Math.multiplyExact(width, height)
                    || !Float.isFinite(threshold) || threshold <= 0 || threshold > 1)
                throw new IllegalArgumentException();
            this.width = width;
            this.height = height;
            this.alpha = alpha.clone();
            this.threshold = threshold;
            this.nearest = nearest;
        }

        private Cutout(float threshold) {
            if (!Float.isFinite(threshold) || threshold <= 0 || threshold > 1) throw new IllegalArgumentException();
            width = 0;
            height = 0;
            alpha = new byte[0];
            this.threshold = threshold;
            nearest = true;
        }

        /**
         * Live block-atlas coverage, including animated frames; UVs remain in atlas space.
         */
        public static Cutout blockAtlas(float threshold) {
            return new Cutout(threshold);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int alpha(int texel) {
            return alpha[texel] & 255;
        }

        public float threshold() {
            return threshold;
        }

        public boolean nearest() {
            return nearest;
        }
    }
}
