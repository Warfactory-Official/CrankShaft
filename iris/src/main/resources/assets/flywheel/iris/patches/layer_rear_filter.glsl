// Luminance-compressed mean, as the pack's own specular filters: single-sample fireflies do not dominate.
const float FLW_REAR_COMPRESSION = 0.25;

vec4 flw_rearRaw(uint node) {
    uvec4 slot = flw_nodes[node + _FLW_REAR_SLOT];
    return vec4(unpackHalf2x16(slot.x), unpackHalf2x16(slot.y));
}

vec3 flw_rearPosition(ivec2 pixel, uint node) {
    return _FLW_REAR_POSITION;
}

vec3 flw_rearNormal(ivec2 pixel, uint node) {
    return _FLW_REAR_NORMAL;
}

// Traced surfaces: behind the pixel's native surface, in front of its opaque one.
bool flw_rearTraced(ivec2 pixel, uint node, float front) {
    float depth = uintBitsToFloat(flw_nodes[node].y);
    return depth != front && (_FLW_REVERSED_DEPTH ? depth > _FLW_REAR_OPAQUE : depth < _FLW_REAR_OPAQUE);
}

vec4 flw_rearCompress(vec4 value) {
    return vec4(value.rgb * pow(length(value.rgb) + 1e-13, FLW_REAR_COMPRESSION - 1.0), value.a);
}

vec4 flw_rearExpand(vec4 value) {
    return vec4(value.rgb * pow(length(value.rgb) + 1e-13, 1.0 / FLW_REAR_COMPRESSION - 1.0), value.a);
}

const uint FLW_REAR_FILTER_LAYERS = 4u;

// Each traced surface averages the traces of neighbouring pixels' surfaces on its own plane: eight taps two pixels
// apart, each neighbour list walked once for all of this pixel's surfaces. Deeper surfaces keep their trace.
void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(pixel, ivec2(screenSize)))) return;
    uint width = uint(screenSize.x);
    float front = texelFetch(depthtex0, pixel, 0).x;
    uint nodes[FLW_REAR_FILTER_LAYERS];
    vec3 positions[FLW_REAR_FILTER_LAYERS];
    vec3 normals[FLW_REAR_FILTER_LAYERS];
    vec4 sums[FLW_REAR_FILTER_LAYERS];
    float weights[FLW_REAR_FILTER_LAYERS];
    uint count = 0u;
    for (uint node = _FLW_REAR_HEADS[uint(pixel.y) * width + uint(pixel.x)]; node != 0xffffffffu;
    node = flw_nodes[node].x) {
        if (!flw_rearTraced(pixel, node, front)) continue;
        if (count == FLW_REAR_FILTER_LAYERS) {
            flw_nodes[node + _FLW_REAR_SLOT].zw = flw_nodes[node + _FLW_REAR_SLOT].xy;
            continue;
        }
        nodes[count] = node;
        positions[count] = flw_rearPosition(pixel, node);
        normals[count] = flw_rearNormal(pixel, node);
        sums[count] = flw_rearCompress(flw_rearRaw(node));
        weights[count] = 1.0;
        count++;
    }
    if (count == 0u) return;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        ivec2 neighbour = pixel + ivec2(x, y) * 2;
        if (x == 0 && y == 0 || any(lessThan(neighbour, ivec2(0)))
            || any(greaterThanEqual(neighbour, ivec2(screenSize)))) continue;
        float neighbourFront = texelFetch(depthtex0, neighbour, 0).x;
        float nearest[FLW_REAR_FILTER_LAYERS];
        uint matches[FLW_REAR_FILTER_LAYERS];
        for (uint i = 0u; i < count; i++) {
            nearest[i] = 0.03;
            matches[i] = 0xffffffffu;
        }
        for (uint other = _FLW_REAR_HEADS[uint(neighbour.y) * width + uint(neighbour.x)]; other != 0xffffffffu;
        other = flw_nodes[other].x) {
            if (!flw_rearTraced(neighbour, other, neighbourFront)) continue;
            vec3 position = flw_rearPosition(neighbour, other);
            vec3 normal = flw_rearNormal(neighbour, other);
            for (uint i = 0u; i < count; i++) {
                float distance = abs(dot(normals[i], position - positions[i]));
                if (distance < nearest[i] && dot(normals[i], normal) > 0.9) {
                    nearest[i] = distance;
                    matches[i] = other;
                }
            }
        }
        float weight = exp(-float(x * x + y * y));
        for (uint i = 0u; i < count; i++) {
            if (matches[i] == 0xffffffffu) continue;
            sums[i] += flw_rearCompress(flw_rearRaw(matches[i])) * weight;
            weights[i] += weight;
        }
    }
    for (uint i = 0u; i < count; i++) {
        vec4 filtered = flw_rearExpand(sums[i] / weights[i]);
        flw_nodes[nodes[i] + _FLW_REAR_SLOT].zw = uvec2(packHalf2x16(filtered.rg), packHalf2x16(filtered.ba));
    }
}
