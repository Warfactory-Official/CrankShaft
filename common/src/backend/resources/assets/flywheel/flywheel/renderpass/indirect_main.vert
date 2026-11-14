// The cull/apply index table; crumbling bypasses culling and neither binds nor declares it.
#ifndef _FLW_CRUMBLING
layout(std430, binding = 2) restrict readonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};
#endif

#if defined(_FLW_BINDLESS) || defined(_FLW_BINDLESS_GL)
#define _FLW_BINDLESS_DRAW
#endif
#if defined(FLW_EMBEDDED) || defined(_FLW_BINDLESS_DRAW) || defined(_FLW_UBER_VERTEX)
layout(std430, binding = 4) restrict readonly buffer DrawBuffer {
    MeshDrawCommand _flw_drawCommands[];
};
layout(std140, binding = 12) uniform _FlwEmbedDraw {
    uint _flw_baseDraw;
};
#endif
#if defined(FLW_EMBEDDED) || defined(_FLW_UBER_VERTEX)
layout(std430, binding = 7) restrict readonly buffer MatrixBuffer {
    Matrices _flw_matrices[];
};
#endif
#ifdef _FLW_BINDLESS_DRAW
flat out uint _flw_texIndex;
#endif
#ifdef _FLW_DEBUG
flat out uvec2 _flw_ids;
#endif
#ifdef _FLW_FAR
out vec3 _flw_farPosition;
flat out uvec2 _flw_farMaterialKey;
layout(std430, binding = 15) restrict readonly buffer FarPartBuffer {
    FarPart _flw_farParts[];
};
#endif
#ifdef _FLW_FAR_RAW_OIT
uniform mat4 _flw_farLayerViewProjection;
uniform vec2 _flw_farLayerDepthTransform;
#endif
#ifdef _FLW_FAR_LAYER_CAPTURE
#include "flywheel:internal/far/layer_storage.glsl"
out vec3 _flw_farLayerQ;
flat out uvec3 _flw_farLayerIdentity;
uniform float _flw_farLayerDensity;
layout(std430, binding = 11) readonly buffer FarLayerDraws { uvec4 _flw_layerDraws[]; };
layout(std430, binding = 14) buffer FarLayerQuadCorners { uvec4 _flw_quadCorners[]; };
#endif
#ifdef _FLW_FAR_BAKE
#ifndef _FLW_FAR_LAYER_CAPTURE
#include "flywheel:internal/far/opaque_bounds.glsl"
#endif
layout(std430, binding = 10) restrict readonly buffer FarBakeJobBuffer {
    FarBakeJob _flw_farBakeJobs[];
};
layout(std430, binding = 12) restrict readonly buffer FarTileBuffer {
    FarTile _flw_farTiles[];
};
out float gl_ClipDistance[4];

// Orthographic view of rest-pose point pos from face (bake & 7) into its rect of the job's scratch pair; depth 1 at the
// face, past 1 in front of it (clamped). A negative face mirrors u, keeping the winding. clip: distances into the face
// rect, the lateral bounds of a brick its part's rows reach past.
vec4 _flw_farBakePosition(vec3 pos, uint bake, out vec4 clip) {
    FarBakeJob job = _flw_farBakeJobs[bake >> 3u];
    uint face = bake & 7u;
    FarPart part = _flw_farParts[job.scratch.w];
    FarTile tile = _flw_farTiles[job.tile.x];
    vec3 q = (pos - part.boxMin.xyz) * uintBitsToFloat(tile.pairs.w);
    #ifdef _FLW_FAR_LAYER_CAPTURE
    uint axis = face >> 1u;
    uint u = (axis + 1u) % 3u, v = (axis + 2u) % 3u;
    vec2 size = vec2(tile.extent[u], tile.extent[v]) + 2.0 * float(_FLW_FAR_LAYER_HALO);
    vec2 texel = vec2((face & 1u) != 0u ? float(tile.extent[u]) - q[u] : q[u], q[v])
            + float(_FLW_FAR_LAYER_HALO);
    clip = vec4(texel, size - texel);
    uint scratch = job.scratch[axis];
    texel += vec2(ivec2(scratch & 0xFFFFu, scratch >> 16u));
    if ((face & 1u) != 0u) texel.x += size.x;
    // Depth is retained in the raw record, not used to clip the support volume at the ownership box.
    return vec4(texel / float(_FLW_FAR_SCRATCH_SIZE) * 2.0 - 1.0, 0.5, 1.0);
    #else
    if (face == 0u) _flw_farIncludeOpaqueVertex(bake >> 3u, pos);
    float depth;
    ivec3 rect;
    ivec2 size;
    vec2 texel = _flw_farFaceTexel(tile, face, q, depth, rect, size);
    clip = vec4(texel - vec2(rect.xy), vec2(rect.xy + size) - texel);
    uint a = face >> 1u;
    uint scratch = job.scratch[a];
    texel += vec2(ivec2(scratch & 0xFFFFu, scratch >> 16u) - _flw_farPairRect(tile, a).xy);
    return vec4(texel / float(_FLW_FAR_SCRATCH_SIZE) * 2.0 - 1.0, 1.0 - _FLW_FAR_DEPTH_SPAN * depth, 1.0);
    #endif
}
#endif

#ifdef _FLW_VK_SINGLE_DRAW
#define _FLW_DRAW_ID 0u
#else
#define _FLW_DRAW_ID uint(gl_DrawIDARB)
#endif

void _flw_layoutVertex() {
    flw_vertexPos = vec4(Position, 1.0);
    flw_vertexColor = Color;
    flw_vertexTexCoord = UV0;
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = vec2(UV2) / 256.0;
    flw_vertexNormal = Normal;
    // Apple GL: FS read of an unwritten output => link error.
    _flw_clipData = vec2(0.0);
}

void main() {
    #ifdef _FLW_CRUMBLING
    uint instanceIndex = uint(gl_BaseInstanceARB) + uint(gl_InstanceID);
    #elif defined(_FLW_FAR_BAKE)
    // Bake items: row and job/face; layered capture also carries the row's stable primitive base.
    #ifdef _FLW_FAR_LAYER_CAPTURE
    uint _flw_farItem = 3u * (uint(gl_BaseInstanceARB) + uint(gl_InstanceID));
    #else
    uint _flw_farItem = 2u * (uint(gl_BaseInstanceARB) + uint(gl_InstanceID));
    #endif
    uint instanceIndex = _flw_instanceIndices[_flw_farItem];
    uint _flw_farBake = _flw_instanceIndices[_flw_farItem + 1u];
    #else
    uint instanceIndex = _flw_instanceIndices[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
    #endif

    #ifdef _FLW_UBER_VERTEX
    // Type-erased path: fetch the command FIRST (the typeId switch needs it before the instance transform).
    MeshDrawCommand _flw_draw = _flw_drawCommands[_flw_baseDraw + _FLW_DRAW_ID];
    _flw_layoutVertex();
    _flw_instanceVertexUber(_flw_draw.packedTexIndices >> 16u, instanceIndex);
    #else
    FlwInstance instance = _flw_unpackInstance(instanceIndex);

    _flw_layoutVertex();
    flw_instanceVertex(instance);
    #endif
    flw_materialVertex();

    #if !defined(_FLW_UBER_VERTEX) && (defined(FLW_EMBEDDED) || defined(_FLW_BINDLESS_DRAW))
    MeshDrawCommand _flw_draw = _flw_drawCommands[_flw_baseDraw + _FLW_DRAW_ID];
    #endif
    #if defined(_FLW_UBER_VERTEX) && !defined(_FLW_FAR)
    // Runtime embedded (mesh-tier-proven; matrixIndex 0 is the reserved identity).
    if (_flw_draw.matrixIndex > 0u) {
        mat4 _flw_embedPose;
        mat3 _flw_embedNormal;
        _flw_unpackMatrices(_flw_matrices[_flw_draw.matrixIndex], _flw_embedPose, _flw_embedNormal);
        flw_vertexPos = _flw_embedPose * flw_vertexPos;
        flw_vertexNormal = _flw_embedNormal * flw_vertexNormal;
    }
    #elif defined(FLW_EMBEDDED)
    _flw_unpackMatrices(_flw_matrices[_flw_draw.matrixIndex], _flw_modelMatrix, _flw_normalMatrix);
    flw_vertexPos = _flw_modelMatrix * flw_vertexPos;
    flw_vertexNormal = _flw_normalMatrix * flw_vertexNormal;
    #endif
    #ifdef _FLW_FAR
    FarPart _flw_farPart = _flw_farParts[_flw_farRowPart(instanceIndex)];
    flw_vertexPos = _flw_farPart.framePose * flw_vertexPos;
    flw_vertexNormal = mat3(_flw_farPart.frameNormal0.xyz, _flw_farPart.frameNormal1.xyz,
            _flw_farPart.frameNormal2.xyz) * flw_vertexNormal;
    if (_flw_farPart.pivot.w != 0.0) {
        #ifdef _FLW_FAR_BAKE
        float _flw_farTurn = -_flw_farPart.axis.w;
        #else
        float _flw_farTurn = _flw_farAngle(_flw_farPart.pivot.w, _flw_farPart.origin.w);
        #endif
        flw_vertexPos.xyz = _flw_farPart.pivot.xyz + _flw_farRotate(flw_vertexPos.xyz - _flw_farPart.pivot.xyz,
                _flw_farPart.axis.xyz, _flw_farTurn);
        flw_vertexNormal = _flw_farRotate(flw_vertexNormal, _flw_farPart.axis.xyz, _flw_farTurn);
    }
    #ifndef _FLW_FAR_BAKE
    flw_vertexPos.xyz += vec3(_flw_farPart.origin.xyz - _flw_renderOrigin.xyz);
    // Light storage holds no far sections: the part's light, as its impostor gets.
    flw_vertexLight = max(flw_vertexLight, vec2(_flw_farPart.info.z & 0xFFFFu, _flw_farPart.info.z >> 16u) / 256.0);
    #endif
    #endif

    flw_vertexNormal = normalize(flw_vertexNormal);
    #ifdef _FLW_FAR
    _flw_farPosition = flw_vertexPos.xyz;
    #ifdef _FLW_FAR_LAYER_CAPTURE
    FarBakeJob layerJob = _flw_farBakeJobs[_flw_farBake >> 3u];
    _flw_farLayerQ = (flw_vertexPos.xyz - _flw_farParts[layerJob.scratch.w].boxMin.xyz) * _flw_farLayerDensity;
    _flw_farLayerIdentity = uvec3(_flw_farBake & 7u, _flw_instanceIndices[_flw_farItem + 2u],
            _flw_baseDraw + _FLW_DRAW_ID);
    uvec4 layerDraw = _flw_layerDraws[_flw_farLayerIdentity.z];
    if (layerDraw.w != 0u) {
        // QuadIndexSequence alone certifies this addressing. Pooled base vertices and each row/mesh's primitive
        // prefix keep different authored quads distinct; arbitrary index sequences never enter this table.
        uint localVertex = uint(gl_VertexID) - _flw_draw.vertexOffset;
        uint source = _flw_farLayerIdentity.y + layerDraw.x + (localVertex >> 2u) * 2u;
        uint corner = source * 2u + (localVertex & 3u);
        uvec3 bits = floatBitsToUint(_flw_farLayerQ);
        // Six face draws revisit the same vertices. Integer atomics make those identical stores well-defined.
        atomicExchange(_flw_quadCorners[corner].x, bits.x);
        atomicExchange(_flw_quadCorners[corner].y, bits.y);
        atomicExchange(_flw_quadCorners[corner].z, bits.z);
        atomicExchange(_flw_quadCorners[corner].w, source + 1u);
    }
    #endif
    _flw_farMaterialKey = uvec2((_flw_draw.packedTexIndices & 0xFFFFu) | (_flw_draw.matrixIndex << 16u),
            (_flw_draw.matrixIndex >> 16u) & 15u);
    #endif

    #ifdef _FLW_CRUMBLING
    _flw_crumblingTexCoord = _flw_getCrumblingTexCoord();
    #endif

    vec4 viewPos = ModelViewMat * flw_vertexPos;
    gl_Position = ProjMat * viewPos;
    #ifdef _FLW_FAR_RAW_OIT
    gl_Position = _flw_farLayerViewProjection * vec4(_flw_farPosition, 1.0);
    gl_Position.z = gl_Position.z * _flw_farLayerDepthTransform.x + gl_Position.w * _flw_farLayerDepthTransform.y;
    #endif
    #ifdef _FLW_FAR_BAKE
    vec4 _flw_farClip;
    gl_Position = _flw_farBakePosition(flw_vertexPos.xyz, _flw_farBake, _flw_farClip);
    for (int i = 0; i < 4; i++) {
        gl_ClipDistance[i] = _flw_farClip[i];
    }
    #endif
    flw_oitViewZ = viewPos.z;

    #if defined(_FLW_BINDLESS_DRAW) || defined(_FLW_UBER_VERTEX)
    _flw_packedMaterial = uvec2(_flw_draw.packedFogAndCutout, _flw_draw.packedMaterialProperties);
    #else
    _flw_packedMaterial = _flw_drawPackedMaterial;
    #endif
    #ifdef _FLW_BINDLESS_DRAW
    _flw_texIndex = _flw_draw.packedTexIndices & 0xFFFFu;
    #endif
    #ifdef _FLW_DEBUG
    #if defined(_FLW_CRUMBLING)
    _flw_ids = uvec2(instanceIndex, 0u);
    #elif defined(_FLW_UBER_VERTEX) || defined(FLW_EMBEDDED) || defined(_FLW_BINDLESS_DRAW)
    _flw_ids = uvec2(instanceIndex, _flw_draw.vertexOffset);
    #else
    _flw_ids = uvec2(instanceIndex, uint(gl_BaseVertex));
    #endif
    #endif
    vertexColor = flw_vertexColor;
    texCoord0 = flw_vertexTexCoord;
    lightCoord = vec2(max(flw_vertexLight.x, _flw_dynamicBlockLight(flw_vertexPos.xyz) / 16.0), flw_vertexLight.y);
    overlayCoord = flw_vertexOverlay;

    vec2 fogDistances = flw_fogDistances(viewPos.xyz, ModelViewMat);
    sphericalVertexDistance = fogDistances.x;
    cylindricalVertexDistance = fogDistances.y;
}
