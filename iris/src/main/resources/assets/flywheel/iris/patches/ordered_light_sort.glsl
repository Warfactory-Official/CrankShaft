// Each invocation owns one column of the head cache; no invocation reads a peer's entries.
shared uvec4 flw_lightHead[16u * 64u];

uvec4 flw_lightHeadGet(uint start, uint index) {
    return flw_lightHead[((start + index) & 15u) * 64u + gl_LocalInvocationIndex];
}

void flw_lightHeadSet(uint start, uint index, uvec4 value) {
    flw_lightHead[((start + index) & 15u) * 64u + gl_LocalInvocationIndex] = value;
}

bool flw_orderedLightSort(inout uint head) {
    uint remaining = head;
    uint sortedHead = 0xffffffffu;
    uint sortedTail = 0xffffffffu;
    uint windowStart = 0u;
    uint windowCount = 0u;
    uint sortedCount = 0u;

    while (remaining != 0xffffffffu) {
        uint node = remaining;
        uvec4 record = flw_nodes[node];
        remaining = record.x;
        uvec4 value = uvec4(node, record.yzw);
        uint insertion = 0u;
        while (insertion < windowCount
                && !flw_layerKeyBefore(value, flw_lightHeadGet(windowStart, insertion))) {
            insertion++;
        }

        if (insertion == 16u && sortedCount > 16u) {
            // The current node still owns its original next link, so the complete suffix remains attached.
            flw_nodes[sortedTail].x = node;
            head = sortedHead;
            return false;
        }

        if (sortedCount == 0u) {
            sortedHead = node;
            sortedTail = node;
            flw_nodes[node].x = 0xffffffffu;
        } else if (insertion == 0u) {
            flw_nodes[node].x = sortedHead;
            sortedHead = node;
        } else if (insertion == windowCount) {
            flw_nodes[sortedTail].x = node;
            flw_nodes[node].x = 0xffffffffu;
            sortedTail = node;
        } else {
            uint previous = flw_lightHeadGet(windowStart, insertion - 1u).x;
            uint next = flw_lightHeadGet(windowStart, insertion).x;
            flw_nodes[previous].x = node;
            flw_nodes[node].x = next;
        }

        if (windowCount < 16u) {
            for (uint i = windowCount; i > insertion; i--)
                flw_lightHeadSet(windowStart, i, flw_lightHeadGet(windowStart, i - 1u));
            flw_lightHeadSet(windowStart, insertion, value);
            windowCount++;
        } else if (insertion == 0u) {
            windowStart = (windowStart + 15u) & 15u;
            flw_lightHeadSet(windowStart, 0u, value);
        } else if (insertion < 16u) {
            for (uint i = 15u; i > insertion; i--)
                flw_lightHeadSet(windowStart, i, flw_lightHeadGet(windowStart, i - 1u));
            flw_lightHeadSet(windowStart, insertion, value);
        }
        sortedCount++;
    }
    head = sortedHead;
    return true;
}
