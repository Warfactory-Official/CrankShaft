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

#ifdef _FLW_GUEST_PREV_INSTANCES
layout(std430, binding = _FLW_PREV_MATRICES_BINDING) restrict readonly buffer _FlwPrevMatrixBuffer {
    Matrices _flw_prevMatrices[];
};
#endif

#ifdef _FLW_GUEST_BINDLESS
layout(std430, binding = _FLW_TEXTURE_HANDLES_BINDING) restrict readonly buffer _FlwTextureHandles {
    uvec2 _flw_textureHandles[];
};

layout(std430, binding = _FLW_GUEST_TEXTURE_INFO_BINDING) restrict readonly buffer _FlwGuestTextureInfo {
    ivec4 _flw_guestTextureInfo[];
};

uint _flw_guestTexSlot;
#endif
#endif

#ifdef _FLW_GUEST_PREV_INSTANCES
layout(std430, binding = _FLW_PREV_MOVED_BINDING) restrict readonly buffer _FlwPrevMovedBuffer {
    uint _flw_movedInstances[];
};
#endif

void _flw_guestVertex() {
    _flw_guestTime(false);
    #ifndef _FLW_CRUMBLING
    uint instanceIndex = _flw_instanceIndices[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
    MeshDrawCommand draw = _flw_drawCommands[_flw_baseDraw + uint(gl_DrawIDARB)];
    #ifdef _FLW_GUEST_BINDLESS
    _flw_guestTexSlot = draw.packedTexIndices & 0xFFFFu;
    #endif
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
    uint instanceIndex = uint(gl_BaseInstanceARB) + uint(gl_InstanceID);
    FlwInstance instance = _flw_unpackInstance(instanceIndex);
    #endif

    #ifdef _FLW_GUEST_PREV_INSTANCES
    // Unwritten under an unchanged pose, an instance stood last frame where it stands now, unless its type animates
    // from time.
    bool moved = (_flw_movedInstances[instanceIndex >> 5u] & 1u << (instanceIndex & 31u)) != 0u;
    #ifdef _FLW_CRUMBLING
    #ifdef _FLW_GUEST_TIME_ANIMATED
    bool previousPass = true;
    #else
    bool previousPass = moved;
    #endif
    #else
    moved = moved || draw.matrixIndex > 0u && _flw_prevMatrices[draw.matrixIndex].pose != pose;
    bool previousPass = moved || _flw_timeAnimatedUber(draw.packedTexIndices >> 16u);
    #endif
    vec4 previousPos;
    if (previousPass) {
        _flw_guestTime(true);
        _flw_meshLayout();
        #ifdef _FLW_CRUMBLING
        if (moved) {
            flw_instanceVertex(_flw_unpackPrevInstance(instanceIndex));
        } else {
            flw_instanceVertex(instance);
        }
        #else
        if (moved) {
            _flw_prevInstanceVertexUber(draw.packedTexIndices >> 16u, instanceIndex);
        } else {
            _flw_instanceVertexUber(draw.packedTexIndices >> 16u, instanceIndex);
        }
        #endif
        flw_materialVertex();
        #ifndef _FLW_CRUMBLING
        if (draw.matrixIndex > 0u) {
            flw_vertexPos = _flw_prevMatrices[draw.matrixIndex].pose * flw_vertexPos;
        }
        #endif
        previousPos = flw_vertexPos;
        _flw_guestTime(false);
    }
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

    _flw_meshLayout();

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

    #ifdef _FLW_GUEST_PREV_INSTANCES
    _flw_guestMotion(previousPass ? previousPos : flw_vertexPos);
    #elif defined(_FLW_GUEST_VELOCITY)
    _flw_guestMotion(flw_vertexPos);
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
