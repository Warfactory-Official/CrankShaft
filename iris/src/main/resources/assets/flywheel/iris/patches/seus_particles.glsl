// SEUS's transparent G-buffer holds one surface (blend off, depth-nearest), and its particle branch (material 111)
// relights the lit background behind that surface, scaled up: a particle behind another particle never reaches the
// image. Composited, each rear particle would relight the result again.
bool flw_seusParticle(uint node) {
    return (flw_nodes[node + 1u].x >> 24u) == 111u;
}

// Keep the particle that would sort last before sorting the retained material records.
void flw_keepNearestParticle(uint pixel, float opaque) {
    uint nearest = 0xffffffffu;
    uint head = 0xffffffffu;
    uint tail = 0xffffffffu;
    for (uint node = flw_heads[pixel]; node != 0xffffffffu;) {
        uint next = flw_nodes[node].x;
        if (flw_seusParticle(node)) {
            float depth = uintBitsToFloat(flw_nodes[node].y);
            if ((_FLW_REVERSED_DEPTH ? depth > opaque : depth < opaque)
                    && (nearest == 0xffffffffu || flw_layerBefore(node, nearest))) nearest = node;
        } else {
            if (tail == 0xffffffffu) head = node;
            else flw_nodes[tail].x = node;
            tail = node;
        }
        node = next;
    }
    if (tail != 0xffffffffu) flw_nodes[tail].x = 0xffffffffu;
    if (nearest != 0xffffffffu) {
        flw_nodes[nearest].x = head;
        head = nearest;
    }
    flw_heads[pixel] = head;
}
