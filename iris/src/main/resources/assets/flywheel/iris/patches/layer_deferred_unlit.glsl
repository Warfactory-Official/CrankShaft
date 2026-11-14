bool flw_farUnlit(ivec2 pixel, float front) {
    for (uint node = flw_farHead(pixel); node != 0xffffffffu; node = flw_nodes[node].x)
    if (uintBitsToFloat(flw_nodes[node].y) == front) return (flw_nodes[node + 1u].w & FLW_LAYER_UNLIT) != 0u;
    return false;
}
