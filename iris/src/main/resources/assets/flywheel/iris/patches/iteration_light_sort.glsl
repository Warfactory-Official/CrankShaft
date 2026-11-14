uint flw_lightMergeSort(uint head, uint length) {
    for (uint run = 1u; run < length; run *= 2u) {
        uint left = head;
        uint tail = 0xffffffffu;
        head = 0xffffffffu;
        while (left != 0xffffffffu) {
            uint right = left;
            uint leftCount = 0u;
            for (uint i = 0u; i < run && right != 0xffffffffu; i++) {
                leftCount++;
                right = flw_nodes[right].x;
            }
            uint rightCount = run;
            while (leftCount > 0u || (rightCount > 0u && right != 0xffffffffu)) {
                uint selected;
                if (leftCount > 0u && (rightCount == 0u || right == 0xffffffffu
                        || flw_layerBefore(left, right))) {
                    selected = left;
                    left = flw_nodes[left].x;
                    leftCount--;
                } else {
                    selected = right;
                    right = flw_nodes[right].x;
                    rightCount--;
                }
                if (tail == 0xffffffffu) head = selected;
                else flw_nodes[tail].x = selected;
                tail = selected;
            }
            left = right;
        }
        flw_nodes[tail].x = 0xffffffffu;
    }
    return head;
}

void flw_sortLight(uvec2 coord) {
    if (!flw_oitActive || flw_lightSimpleInitialized(ivec2(coord), uvec2(screenSize))) return;
    uint pixel = coord.y * uint(screenSize.x) + coord.x;
    uint head = flw_heads[pixel];
    vec3 sum = vec3(0.0);
    float nearest = _FLW_REVERSED_DEPTH ? 0.0 : 1.0;
    float farthest = _FLW_REVERSED_DEPTH ? 1.0 : 0.0;
    uint node = head;
    while (node != 0xffffffffu) {
        uvec4 record = flw_nodes[node];
        if ((record.w >> 16u) != 0u) break;
        sum += vec3(unpackHalf2x16(record.z), unpackHalf2x16(record.w).x);
        float depth = uintBitsToFloat(record.y);
        nearest = _FLW_REVERSED_DEPTH ? max(nearest, depth) : min(nearest, depth);
        farthest = _FLW_REVERSED_DEPTH ? min(farthest, depth) : max(farthest, depth);
        node = record.x;
    }
    if (head == 0xffffffffu) return;
    if (node == 0xffffffffu) {
        uint summary = flw_lightSummaryOffset(pixel, uvec2(screenSize));
        uint stride = uint(screenSize.x) * uint(screenSize.y);
        flw_heads[summary] = floatBitsToUint(sum.r);
        flw_heads[summary + stride] = floatBitsToUint(sum.g);
        flw_heads[summary + 2u * stride] = floatBitsToUint(sum.b);
        flw_heads[summary + 3u * stride] = floatBitsToUint(nearest);
        flw_heads[summary + 4u * stride] = floatBitsToUint(farthest);
        flw_heads[pixel] |= FLW_LIGHT_UNSORTED;
        return;
    }
    if (!flw_orderedLightSort(head)) {
        uint length = 0u;
        for (node = head; node != 0xffffffffu; node = flw_nodes[node].x) length++;
        head = flw_lightMergeSort(head, length);
    }
    flw_heads[pixel] = head;
}
