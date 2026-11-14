#include "flywheel:internal/uniforms/uniforms.glsl"
#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/submission.glsl"
#include "flywheel:internal/far/frustum.glsl"
#include "flywheel:internal/far/occlusion.glsl"

layout(local_size_x = 64) in;

layout(std430, binding = 5) restrict readonly buffer EntryLevelBuffer {
    uint _flw_farEntryLevels[];
};

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

layout(std430, binding = 11) restrict writeonly buffer ImpostorBuffer {
    uint _flw_farImpostors[];
};

layout(std430, binding = 12) restrict readonly buffer TileBuffer {
    FarTile _flw_farTiles[];
};

// Per part slot: baked parts of impostor-level entries inside the frustum and not occluded join the impostor draw.
void main() {
    uint slot = gl_GlobalInvocationID.x;
    if (slot >= _flw_farPartCount) {
        return;
    }
    FarPart part = _flw_farParts[slot];
    uint baked = _FLW_FAR_PART_LIVE | _FLW_FAR_PART_BAKED;
    if ((part.info.w & baked) != baked || _flw_farEntryLevels[part.info.x] != _FLW_FAR_LEVEL_IMPOSTOR) {
        return;
    }
    FarTile tile = _flw_farTiles[part.info.y];
    vec3 size = vec3(tile.extent.xyz) / uintBitsToFloat(tile.pairs.w);
    vec3 halfSize = size * 0.5;
    vec3 halfBounds = halfSize;
    vec3 center = part.boxMin.xyz + halfSize;
    if (part.pivot.w != 0.0) {
        float angle = part.axis.w + _flw_farAngle(part.pivot.w, part.origin.w);
        mat3 rotation = mat3(_flw_farRotate(vec3(1.0, 0.0, 0.0), part.axis.xyz, angle),
                _flw_farRotate(vec3(0.0, 1.0, 0.0), part.axis.xyz, angle),
                _flw_farRotate(vec3(0.0, 0.0, 1.0), part.axis.xyz, angle));
        center = part.pivot.xyz + rotation * (center - part.pivot.xyz);
        halfBounds = abs(rotation[0]) * halfSize.x + abs(rotation[1]) * halfSize.y
                + abs(rotation[2]) * halfSize.z;
    }
    center += vec3(part.origin.xyz - _flw_renderOrigin.xyz);
    float radius = length(halfSize);
    if (_flw_farTestSphere(center, radius) && !_flw_farOccluded(center - halfBounds, center + halfBounds)) {
        _flw_farImpostors[atomicAdd(_flw_farBoxInstanceCount, 1u)] = slot;
    }
}
