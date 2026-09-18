uniform sampler2D _flw_geometryAtlas;

vec3 _flw_aoVec3(uint at) {
    return uintBitsToFloat(uvec3(_flw_indexLut(at), _flw_indexLut(at + 1u), _flw_indexLut(at + 2u)));
}

bool _flw_aoBox(uint at, vec3 origin, vec3 inverseDirection, float reach) {
    vec3 a = (_flw_aoVec3(at) - origin) * inverseDirection;
    vec3 b = (_flw_aoVec3(at + 4u) - origin) * inverseDirection;
    vec3 near = min(a, b);
    vec3 far = max(a, b);
    return max(max(near.x, near.y), max(near.z, 0.)) <= min(min(far.x, far.y), min(far.z, reach));
}

vec3 _flw_aoInverseDirection(vec3 direction) {
    return 1. / mix(vec3(1e-30), direction, greaterThan(abs(direction), vec3(1e-30)));
}

float _flw_aoAlpha(uint mask, ivec2 pixel, ivec2 size) {
    pixel = (pixel + size) % size;
    uint texel = uint(pixel.x + pixel.y * size.x);
    return float((_flw_indexLut(mask + 3u + (texel >> 2u)) >> ((texel & 3u) * 8u)) & 255u) / 255.;
}

bool _flw_aoCovered(uint mask, vec3 uvAlpha) {
    uint heightAndFilter = _flw_indexLut(mask + 1u);
    ivec2 size = ivec2(_flw_indexLut(mask), heightAndFilter & 0x7FFFFFFFu);
    if (size.x == 0) {
        return textureLod(_flw_geometryAtlas, uvAlpha.xy, 0.).a * uvAlpha.z >= uintBitsToFloat(_flw_indexLut(mask + 2u));
    }
    if ((heightAndFilter & 0x80000000u) != 0u) {
        return _flw_aoAlpha(mask, ivec2(fract(uvAlpha.xy) * vec2(size)), size) * uvAlpha.z >= uintBitsToFloat(_flw_indexLut(mask + 2u));
    }
    vec2 position = fract(uvAlpha.xy) * vec2(size) - .5;
    ivec2 pixel = ivec2(floor(position));
    vec2 fraction = fract(position);
    float alpha = mix(mix(_flw_aoAlpha(mask, pixel, size), _flw_aoAlpha(mask, pixel + ivec2(1, 0), size), fraction.x),
                      mix(_flw_aoAlpha(mask, pixel + ivec2(0, 1), size), _flw_aoAlpha(mask, pixel + ivec2(1, 1), size), fraction.x), fraction.y);
    return alpha * uvAlpha.z >= uintBitsToFloat(_flw_indexLut(mask + 2u));
}

bool _flw_aoTriangle(uint at, uint mask, vec3 origin, vec3 direction, float reach, vec4 uvRegion, vec2 uvShear, float alpha, bool clipped, vec4 clipPlane, float clipMax) {
    vec3 a = _flw_aoVec3(at);
    vec3 e1 = _flw_aoVec3(at + 3u) - a;
    vec3 e2 = _flw_aoVec3(at + 6u) - a;
    vec3 p = cross(direction, e2);
    float determinant = dot(e1, p);
    if (determinant == 0.) return false;
    vec3 relative = origin - a;
    float u = dot(relative, p) / determinant;
    if (u < 0. || u > 1.) return false;
    vec3 q = cross(relative, e1);
    float v = dot(direction, q) / determinant;
    if (v < 0. || u + v > 1.) return false;
    float distance = dot(e2, q) / determinant;
    if (distance <= 0. || distance >= reach) return false;
    if (clipped) {
        float coordinate = dot(clipPlane.xyz, origin + distance * direction);
        if (coordinate < clipPlane.w || coordinate > clipMax) return false;
    }
    if (mask == 0u) return true;
    vec3 uv0 = _flw_aoVec3(at + 9u);
    vec3 uv1 = _flw_aoVec3(at + 12u);
    vec3 uv2 = _flw_aoVec3(at + 15u);
    vec3 uvAlpha = uv0 + u * (uv1 - uv0) + v * (uv2 - uv0);
    uvAlpha.xy = uvRegion.xy + uvAlpha.xy * uvRegion.zw + uvAlpha.yx * uvShear;
    uvAlpha.z *= alpha;
    return _flw_aoCovered(mask, uvAlpha);
}

bool _flw_aoMesh(uint scene, uint instance, vec3 origin, vec3 direction, float reach) {
    uint nodes = scene + _flw_indexLut(instance);
    uint count = _flw_indexLut(instance + 1u);
    uint triangles = scene + _flw_indexLut(instance + 2u);
    uint mask = _flw_indexLut(instance + 3u);
    bool clipped = _flw_indexLut(instance + 7u) != 0u;
    vec4 clipPlane = vec4(0.);
    float clipMax = 0.;
    if (clipped) {
        clipPlane = uintBitsToFloat(uvec4(_flw_indexLut(instance + 32u), _flw_indexLut(instance + 33u),
                _flw_indexLut(instance + 34u), _flw_indexLut(instance + 35u)));
        clipMax = uintBitsToFloat(_flw_indexLut(instance + 36u));
        if (clipPlane.w > clipMax) return false;
    }
    uint stride = mask == 0u ? 9u : 18u;
    if (mask != 0u) mask += scene;
    vec4 uvRegion = uintBitsToFloat(uvec4(_flw_indexLut(instance + 24u), _flw_indexLut(instance + 25u),
            _flw_indexLut(instance + 26u), _flw_indexLut(instance + 27u)));
    float alpha = uintBitsToFloat(_flw_indexLut(instance + 28u));
    vec2 uvShear = uintBitsToFloat(uvec2(_flw_indexLut(instance + 29u), _flw_indexLut(instance + 30u)));
    origin -= _flw_aoVec3(instance + 4u);
    uint at = instance + 8u;
    mat4 inversePose = mat4(
        uintBitsToFloat(uvec4(_flw_indexLut(at), _flw_indexLut(at + 1u), _flw_indexLut(at + 2u), _flw_indexLut(at + 3u))),
        uintBitsToFloat(uvec4(_flw_indexLut(at + 4u), _flw_indexLut(at + 5u), _flw_indexLut(at + 6u), _flw_indexLut(at + 7u))),
        uintBitsToFloat(uvec4(_flw_indexLut(at + 8u), _flw_indexLut(at + 9u), _flw_indexLut(at + 10u), _flw_indexLut(at + 11u))),
        uintBitsToFloat(uvec4(_flw_indexLut(at + 12u), _flw_indexLut(at + 13u), _flw_indexLut(at + 14u), _flw_indexLut(at + 15u))));
    origin = (inversePose * vec4(origin, 1.)).xyz;
    direction = (inversePose * vec4(direction, 0.)).xyz;
    // Keep the ray parameter in world units under non-uniform instance scales.
    vec3 inverseDirection = _flw_aoInverseDirection(direction);
    uint node = 0u;
    while (node < count) {
        uint entry = nodes + node * 8u;
        if (!_flw_aoBox(entry, origin, inverseDirection, reach)) {
            node = _flw_indexLut(entry + 3u);
            continue;
        }
        uint triangle = _flw_indexLut(entry + 7u);
        if (triangle != 0u && _flw_aoTriangle(triangles + (triangle - 1u) * stride, mask, origin, direction, reach, uvRegion, uvShear, alpha, clipped, clipPlane, clipMax)) return true;
        node++;
    }
    return false;
}

bool _flw_aoOccluded(uint scene, vec3 origin, vec3 direction, float reach) {
    uint nodes = scene + _flw_indexLut(scene);
    uint count = _flw_indexLut(scene + 1u);
    uint instances = scene + _flw_indexLut(scene + 2u);
    vec3 inverseDirection = _flw_aoInverseDirection(direction);
    uint node = 0u;
    while (node < count) {
        uint entry = nodes + node * 8u;
        if (!_flw_aoBox(entry, origin, inverseDirection, reach)) {
            node = _flw_indexLut(entry + 3u);
            continue;
        }
        uint instance = _flw_indexLut(entry + 7u);
        if (instance != 0u && _flw_aoMesh(scene, instances + (instance - 1u) * 40u, origin, direction, reach)) return true;
        node++;
    }
    return false;
}

// Position uses the renderer's origin; registered poses use integer world anchors.
// Reach and bias are world distances. Samples integrate cosine-weighted hemisphere visibility.
float flw_geometryOcclusion(vec3 position, vec3 normal, float reach, float bias, uint samples) {
    uint scene = _flw_indexLut(0u);
    if (scene == 0u) return 1.;
    if (_flw_indexLut(scene + 7u) == 0u) return 1.;
    ivec3 sceneOrigin = ivec3(_flw_indexLut(scene + 4u), _flw_indexLut(scene + 5u), _flw_indexLut(scene + 6u));
    position += vec3(flw_renderOrigin - sceneOrigin);
    normal = normalize(normal);
    vec3 tangent = normalize(cross(normal, abs(normal.y) < .999 ? vec3(0., 1., 0.) : vec3(1., 0., 0.)));
    vec3 bitangent = cross(normal, tangent);
    vec3 origin = position + normal * bias;
    uint blocked = 0u;
    for (uint i = 0u; i < samples; i++) {
        float r2 = (float(i) + .5) / float(samples);
        float angle = float(i) * 2.399963229728653;
        vec3 direction = sqrt(r2) * (cos(angle) * tangent + sin(angle) * bitangent) + sqrt(1. - r2) * normal;
        if (_flw_aoOccluded(scene, origin, direction, reach)) blocked++;
    }
    return 1. - float(blocked) / float(samples);
}
