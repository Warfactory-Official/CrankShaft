#ifndef _FLW_CRUMBLING
layout(std430, binding = 2) restrict readonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};

layout(std430, binding = 4) restrict readonly buffer DrawBuffer {
    MeshDrawCommand _flw_drawCommands[];
};

layout(std140, binding = 12) uniform _FlwEmbedDraw {
    uint _flw_baseDraw;
};

layout(std430, binding = 7) restrict readonly buffer MatrixBuffer {
    Matrices _flw_matrices[];
};
#endif

void _flw_guestVertex() {
    #ifndef _FLW_CRUMBLING
    uint instanceIndex = _flw_instanceIndices[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
    MeshDrawCommand draw = _flw_drawCommands[_flw_baseDraw + uint(gl_DrawIDARB)];
    // IndirectDraw writes the tags into meshletBase / vertexCount under a guest.
    _flw_drawTag = draw.meshletBase;
    _flw_itemTag = draw.vertexCount;
    mat4 pose;
    mat3 normal;
    if (draw.matrixIndex > 0u) {
        _flw_unpackMatrices(_flw_matrices[draw.matrixIndex], pose, normal);
    }
    #else
    _flw_drawTag = 0u;
    _flw_itemTag = 0u;
    FlwInstance instance = _flw_unpackInstance(uint(gl_BaseInstanceARB) + uint(gl_InstanceID));
    #endif

    #ifdef _FLW_GUEST_PROXY_VERTEX
    _flw_proxyLayout();
    #ifdef _FLW_CRUMBLING
    flw_instanceVertex(instance);
    #else
    _flw_instanceVertexUber(draw.packedTexIndices >> 16u, instanceIndex);
    #endif
    flw_materialVertex();
    #ifndef _FLW_CRUMBLING
    if (draw.matrixIndex > 0u) {
        flw_vertexPos = pose * flw_vertexPos;
        flw_vertexNormal = normal * flw_vertexNormal;
    }
    #endif
    _flw_proxyCapture();
    #endif

    flw_vertexPos = vec4(_flw_aPosition, 1.0);
    flw_vertexColor = _flw_aColor;
    flw_vertexTexCoord = _flw_aTexCoord;
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = vec2(_flw_aLight) / 256.0;
    flw_vertexNormal = _flw_guestNormal();

    #ifdef _FLW_CRUMBLING
    flw_instanceVertex(instance);
    #else
    _flw_instanceVertexUber(draw.packedTexIndices >> 16u, instanceIndex);
    #endif
    flw_materialVertex();

    #ifndef _FLW_CRUMBLING
    if (draw.matrixIndex > 0u) {
        flw_vertexPos = pose * flw_vertexPos;
        flw_vertexNormal = normal * flw_vertexNormal;
    }
    #endif

    flw_vertexNormal = normalize(flw_vertexNormal);
    #ifdef _FLW_GUEST_VERTEX_LIGHT
    _flw_guestVertexLight(draw.packedMaterialProperties);
    #endif
    #ifdef _FLW_GUEST_EMISSIVE_PEAK
    flw_vertexColor.rgb /= max(max(flw_vertexColor.r, flw_vertexColor.g), max(flw_vertexColor.b, 1.0 / 255.0));
    #endif
    #if !defined(_FLW_GUEST_MESH_LIGHT) && !defined(_FLW_CRUMBLING)
    if ((draw.packedMaterialProperties & _FLW_USE_LIGHT_MASK) == 0u) {
        flw_vertexLight = _FLW_GUEST_UNLIT_LIGHT;
    }
    #endif
    #ifdef _FLW_GUEST_PROXY_VERTEX
    _flw_proxyFinish();
    #endif

    #ifdef _FLW_CRUMBLING
    flw_vertexTexCoord = _flw_getCrumblingTexCoord();
    #endif

    #ifdef _FLW_GUEST_CONTRACT
    clrwl_vertexTangent = _flw_guestTangent();
    #ifdef _FLW_CRUMBLING
    _flw_packedMaterial = _flw_drawPackedMaterial;
    #else
    _flw_packedMaterial = uvec2(draw.packedFogAndCutout, draw.packedMaterialProperties);
    #endif
    #endif
    #ifdef _FLW_GUEST_OIT
    _flw_oitViewZ = (flw_view * flw_vertexPos).z;
    #endif
}
