#include "flywheel:internal/far/layer_storage.glsl"

layout(std430,binding=0) buffer FarLayerHeads { uint _flw_layerHeads[]; };
layout(std430,binding=3) buffer FarLayerNodes { FarLayerNode _flw_layerNodes[]; };
layout(std430,binding=7) coherent buffer FarLayerStatus { uvec4 _flw_layerStatus[6]; };
layout(std430,binding=9) buffer FarLayerSurfaces { FarLayerSurface _flw_layerSurfaces[]; };
layout(std430,binding=11) readonly buffer FarLayerDraws { uvec4 _flw_layerDraws[]; };

uniform uint _flw_farLayerNodeCapacity;
uniform vec3 _flw_farLayerExtent;
in vec3 _flw_farLayerQ;
flat in uvec3 _flw_farLayerIdentity; // face, row primitive base, draw index

void _flw_farCaptureLayer(vec4 color, vec3 normal, vec4 tangent, vec2 light, float diffuse, float unlit,
                         uvec4 material) {
    vec3 q = _flw_farLayerQ;
    vec3 geometricNormal = cross(dFdx(q), dFdy(q));
    if (!gl_FrontFacing) geometricNormal = -geometricNormal;
    // Canonicalize the geometric plane, independently of the authored shading normal. Axis-aligned primitives
    // then retain an exact unit coefficient and their exact constant coordinate, including a brick boundary.
    geometricNormal /= max(abs(geometricNormal.x), max(abs(geometricNormal.y), abs(geometricNormal.z)));
    vec4 plane = vec4(geometricNormal, -dot(geometricNormal, q));
    // Capture support may overlap neighbouring bricks. Only reconstruction applies half-open hit ownership.
    float halo = float(_FLW_FAR_LAYER_HALO);
    if (any(lessThan(q, vec3(-halo)))
            || any(greaterThan(q, _flw_farLayerExtent + halo))) return;
    // Bounded allocation follows the insert chain's saturating pool policy; an incomplete bake is never published.
    if (_flw_layerStatus[0].x >= _flw_farLayerNodeCapacity) {
        atomicOr(_flw_layerStatus[0].y, 1u);
        return;
    }
    uint node = atomicAdd(_flw_layerStatus[0].x, 1u);
    if (node >= _flw_farLayerNodeCapacity) {
        atomicOr(_flw_layerStatus[0].y, 1u);
        return;
    }
    uvec4 draw = _flw_layerDraws[_flw_farLayerIdentity.z];
    uint source = _flw_farLayerIdentity.y + draw.x + uint(gl_PrimitiveID) % draw.z;
    if (atomicCompSwap(_flw_layerSurfaces[source].info.w, 0u, 1u) == 0u) {
        _flw_layerSurfaces[source].plane = plane;
        _flw_layerSurfaces[source].info.xyz = uvec3(draw.y, source, 0u);
    }
    uint face = _flw_farLayerIdentity.x;
    uint axis = face >> 1u;
    float depth = (face & 1u) != 0u ? q[axis] : _flw_farLayerExtent[axis] - q[axis];
    depth /= _flw_farLayerExtent[axis];
    uint pixel = uint(gl_FragCoord.y) * 1024u + uint(gl_FragCoord.x);
    uint head = atomicExchange(_flw_layerHeads[pixel], node);
    _flw_layerNodes[node].link = uvec4(head, source + 1u, draw.y, 0u);
    _flw_layerNodes[node].color = color;
    _flw_layerNodes[node].normal = vec4(normal * 0.5 + 0.5, diffuse);
    _flw_layerNodes[node].light = vec4(light, unlit, 1.0);
    _flw_layerNodes[node].material = material;
    _flw_layerNodes[node].plane = plane;
    _flw_layerNodes[node].depth = vec4(depth, depth, 0.0, 0.0);
}
