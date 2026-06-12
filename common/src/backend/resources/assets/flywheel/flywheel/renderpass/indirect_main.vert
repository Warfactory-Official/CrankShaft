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

void _flw_layoutVertex() {
    flw_vertexPos = vec4(Position, 1.0);
    flw_vertexColor = Color;
    flw_vertexTexCoord = UV0;
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = (vec2(UV2) + 8.0) / 256.0;
    flw_vertexNormal = Normal;
}

void main() {
    #ifdef _FLW_CRUMBLING
    uint instanceIndex = uint(gl_BaseInstanceARB) + uint(gl_InstanceID);
    #else
    uint instanceIndex = _flw_instanceIndices[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
    #endif

    #ifdef _FLW_UBER_VERTEX
    // Type-erased path: fetch the command FIRST (the typeId switch needs it before the instance transform).
    MeshDrawCommand _flw_draw = _flw_drawCommands[_flw_baseDraw + uint(gl_DrawIDARB)];
    _flw_layoutVertex();
    _flw_instanceVertexUber(_flw_draw.packedTexIndices >> 16u, instanceIndex);
    #else
    FlwInstance instance = _flw_unpackInstance(instanceIndex);

    _flw_layoutVertex();
    flw_instanceVertex(instance);
    #endif
    flw_materialVertex();

    #if !defined(_FLW_UBER_VERTEX) && (defined(FLW_EMBEDDED) || defined(_FLW_BINDLESS_DRAW))
    MeshDrawCommand _flw_draw = _flw_drawCommands[_flw_baseDraw + uint(gl_DrawIDARB)];
    #endif
    #ifdef _FLW_UBER_VERTEX
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

    flw_vertexNormal = normalize(flw_vertexNormal);

    #ifdef _FLW_CRUMBLING
    _flw_crumblingTexCoord = _flw_getCrumblingTexCoord();
    #endif

    vec4 viewPos = ModelViewMat * flw_vertexPos;
    gl_Position = ProjMat * viewPos;
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
    lightCoord = flw_vertexLight;
    overlayCoord = flw_vertexOverlay;

    sphericalVertexDistance = fog_spherical_distance(viewPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPos.xyz);
}
