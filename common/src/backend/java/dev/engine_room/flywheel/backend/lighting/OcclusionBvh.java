package dev.engine_room.flywheel.backend.lighting;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.BitSet;

final class OcclusionBvh {
    static final int NODE_WORDS = 8;

    private OcclusionBvh() {
    }

    static int[] build(float[] bounds) {
        int[] order = new int[bounds.length / 6];
        for (int i = 0; i < order.length; i++) order[i] = i;
        var nodes = new IntArrayList(Math.max(0, order.length * 2 - 1) * NODE_WORDS);
        if (order.length != 0) append(bounds, order, 0, order.length, -1, nodes, null, null);
        return nodes.toIntArray();
    }

    static Topology buildTopology(float[] bounds) {
        int[] order = new int[bounds.length / 6];
        for (int i = 0; i < order.length; i++) order[i] = i;
        var nodes = new IntArrayList(Math.max(0, order.length * 2 - 1) * NODE_WORDS);
        int[] parents = new int[Math.max(0, order.length * 2 - 1)];
        int[] leaves = new int[order.length];
        if (order.length != 0) append(bounds, order, 0, order.length, -1, nodes, parents, leaves);
        return new Topology(nodes.toIntArray(), parents, leaves);
    }

    static void refit(int[] nodes, float[] bounds) {
        for (int at = nodes.length - NODE_WORDS; at >= 0; at -= NODE_WORDS) {
            refitNode(nodes, bounds, at);
        }
    }

    static void refitSparse(Topology topology, float[] bounds, IntArrayList changed, IntArrayList touched,
                            BitSet marked) {
        touched.clear();
        marked.clear();
        for (int i = 0; i < changed.size(); i++) {
            for (int node = topology.leaves[changed.getInt(i)]; node >= 0; node = topology.parents[node]) {
                if (marked.get(node)) break;
                marked.set(node);
                touched.add(node);
            }
        }
        touched.sort(null);
        for (int i = touched.size() - 1; i >= 0; i--) {
            refitNode(topology.nodes, bounds, touched.getInt(i) * NODE_WORDS);
        }
    }

    private static void refitNode(int[] nodes, float[] bounds, int at) {
        int item = nodes[at + 7] - 1;
        if (item >= 0) {
            for (int axis = 0; axis < 3; axis++) {
                nodes[at + axis] = Float.floatToRawIntBits(bounds[item * 6 + axis]);
                nodes[at + 4 + axis] = Float.floatToRawIntBits(bounds[item * 6 + 3 + axis]);
            }
        } else {
            int left = at + NODE_WORDS;
            int right = nodes[left + 3] * NODE_WORDS;
            for (int axis = 0; axis < 3; axis++) {
                nodes[at + axis] = Float.floatToRawIntBits(Math.min(Float.intBitsToFloat(nodes[left + axis]),
                        Float.intBitsToFloat(nodes[right + axis])));
                nodes[at + 4 + axis] = Float.floatToRawIntBits(Math.max(Float.intBitsToFloat(nodes[left + 4 + axis]),
                        Float.intBitsToFloat(nodes[right + 4 + axis])));
            }
        }
    }

    private static void append(float[] bounds, int[] order, int from, int to, int parent,
                               IntArrayList nodes, int[] parents, int[] leaves) {
        int at = nodes.size();
        int node = at / NODE_WORDS;
        if (parents != null) parents[node] = parent;
        nodes.size(at + NODE_WORDS);
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = from; i < to; i++) {
            int p = order[i] * 6;
            minX = Math.min(minX, bounds[p]);
            minY = Math.min(minY, bounds[p + 1]);
            minZ = Math.min(minZ, bounds[p + 2]);
            maxX = Math.max(maxX, bounds[p + 3]);
            maxY = Math.max(maxY, bounds[p + 4]);
            maxZ = Math.max(maxZ, bounds[p + 5]);
        }
        nodes.set(at, Float.floatToRawIntBits(minX));
        nodes.set(at + 1, Float.floatToRawIntBits(minY));
        nodes.set(at + 2, Float.floatToRawIntBits(minZ));
        nodes.set(at + 4, Float.floatToRawIntBits(maxX));
        nodes.set(at + 5, Float.floatToRawIntBits(maxY));
        nodes.set(at + 6, Float.floatToRawIntBits(maxZ));
        if (to - from == 1) {
            nodes.set(at + 7, order[from] + 1);
            if (leaves != null) leaves[order[from]] = node;
        } else {
            float x = maxX - minX, y = maxY - minY, z = maxZ - minZ;
            int axis = x >= y && x >= z ? 0 : y >= z ? 1 : 2;
            IntArrays.quickSort(order, from, to, (a, b) -> Float.compare(
                    bounds[a * 6 + axis] + bounds[a * 6 + axis + 3],
                    bounds[b * 6 + axis] + bounds[b * 6 + axis + 3]));
            int middle = (from + to) >>> 1;
            append(bounds, order, from, middle, node, nodes, parents, leaves);
            append(bounds, order, middle, to, node, nodes, parents, leaves);
        }
        // Preorder escape indices let both GPU traversal levels advance without a per-ray stack.
        nodes.set(at + 3, nodes.size() / NODE_WORDS);
    }

    record Topology(int[] nodes, int[] parents, int[] leaves) {
    }
}
