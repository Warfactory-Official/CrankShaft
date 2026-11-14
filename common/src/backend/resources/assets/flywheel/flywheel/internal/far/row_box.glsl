// Per model: model-space box min, max.
layout(std430, binding = 7) restrict readonly buffer ModelBoundsBuffer {
    vec4 _flw_farModelBounds[];
};

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

// The rest-pose box of a row's model box corners, each posed as a zero-radius sphere and turned back by its part.
void _flw_farRowBox(uvec4 row, FarPart part, out vec3 lo, out vec3 hi) {
    vec3 a = _flw_farModelBounds[row.y * 2u].xyz;
    vec3 b = _flw_farModelBounds[row.y * 2u + 1u].xyz;
    lo = vec3(3.0e38);
    hi = vec3(-3.0e38);
    for (uint i = 0u; i < 8u; i++) {
        vec3 corner = mix(a, b, vec3(uvec3(i, i >> 1u, i >> 2u) & 1u));
        float radius = 0.0;
        _flw_transformBoundingSphereUber(row.z, row.x, corner, radius);
        corner = (part.framePose * vec4(corner, 1.0)).xyz;
        if (part.pivot.w != 0.0) {
            corner = part.pivot.xyz + _flw_farRotate(corner - part.pivot.xyz, part.axis.xyz, -part.axis.w);
        }
        lo = min(lo, corner);
        hi = max(hi, corner);
    }
}
