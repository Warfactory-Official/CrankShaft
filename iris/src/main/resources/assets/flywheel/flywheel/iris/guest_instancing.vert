#ifdef FLW_EMBEDDED
layout(std140) uniform _FlwEmbed {
    mat4 _flw_embedPose;
    mat4 _flw_embedNormal;
    #ifdef _FLW_GUEST_PREV_INSTANCES
    mat4 _flw_embedPosePrev;
    #endif
};
#endif

#ifdef _FLW_GUEST_PREV_INSTANCES
uniform usamplerBuffer _flw_movedInstances;
#endif

#ifdef _FLW_GUEST_STORAGE_INSTANCES
layout(std140) uniform _FlwGuestInstanceRange {
    uvec4 _flw_guestInstanceRange;
};
#endif

#ifdef _FLW_INSTANCING_SELECTOR
layout(std140) uniform _FlwInstanceSelector {
    ivec4 _flw_instanceSelector;
};
#endif

#ifdef _FLW_GUEST_VISIBLE_INSTANCES
layout(std430, binding = 1) readonly buffer _FlwVisibleInstances {
    uint _flw_visibleInstances[];
};
#endif

void _flw_guestVertex() {
    _flw_guestTime(false);
    #ifdef _FLW_GUEST_STORAGE_INSTANCES
    uint instanceIndex = uint(gl_InstanceID);
    #ifdef _FLW_CRUMBLING
    instanceIndex += uint(gl_BaseInstanceARB);
    #elif defined(_FLW_GUEST_VISIBLE_INSTANCES)
    uint instanceOffset = _flw_visibleInstances[_flw_guestInstanceRange.y + instanceIndex];
    #endif
    #if !defined(_FLW_GUEST_VISIBLE_INSTANCES) || defined(_FLW_CRUMBLING)
    uint instanceOffset = _flw_guestInstanceRange.x + instanceIndex * _FLW_GUEST_INSTANCE_UINTS;
    #endif
    FlwInstance instance = _flw_unpackInstance(instanceOffset);
    #else
    #ifdef _FLW_INSTANCING_SELECTOR
    int instanceSlot = _flw_instanceSelector.x + gl_InstanceID;
    #elif defined(_FLW_CRUMBLING)
    int instanceSlot = gl_BaseInstanceARB + gl_InstanceID;
    #else
    int instanceSlot = gl_InstanceID;
    #endif
    FlwInstance instance = _flw_unpackInstance(instanceSlot);
    #endif

    _flw_drawTag = _flw_drawPackedMaterial.x;
    _flw_itemTag = _flw_drawItemTag;
    #ifdef FLW_EMBEDDED
    _flw_modelMatrix = _flw_embedPose;
    _flw_normalMatrix = mat3(_flw_embedNormal);
    #endif

    #ifdef _FLW_GUEST_PREV_INSTANCES
    // Unwritten under an unchanged pose, an instance stood last frame where it stands now, unless its type animates
    // from time.
    int movedWord = instanceSlot >> 5;
    bool moved = movedWord < textureSize(_flw_movedInstances)
            && (texelFetch(_flw_movedInstances, movedWord).x & 1u << uint(instanceSlot & 31)) != 0u;
    #ifdef FLW_EMBEDDED
    moved = moved || _flw_embedPosePrev != _flw_embedPose;
    #endif
    #ifdef _FLW_GUEST_TIME_ANIMATED
    bool previousPass = true;
    #else
    bool previousPass = moved;
    #endif
    vec4 previousPos;
    if (previousPass) {
        _flw_guestTime(true);
        _flw_meshLayout();
        if (moved) {
            flw_instanceVertex(_flw_unpackPrevInstance(instanceSlot));
        } else {
            flw_instanceVertex(instance);
        }
        flw_materialVertex();
        #ifdef FLW_EMBEDDED
        flw_vertexPos = _flw_embedPosePrev * flw_vertexPos;
        #endif
        previousPos = flw_vertexPos;
        _flw_guestTime(false);
    }
    #endif

    #ifdef _FLW_GUEST_PROXY_VERTEX
    _flw_proxyLayout();
    flw_instanceVertex(instance);
    flw_materialVertex();
    #ifdef FLW_EMBEDDED
    flw_vertexPos = _flw_modelMatrix * flw_vertexPos;
    flw_vertexNormal = _flw_normalMatrix * flw_vertexNormal;
    #endif
    _flw_proxyCapture();
    #endif

    _flw_meshLayout();

    flw_instanceVertex(instance);
    flw_materialVertex();

    #ifdef FLW_EMBEDDED
    flw_vertexPos = _flw_modelMatrix * flw_vertexPos;
    flw_vertexNormal = _flw_normalMatrix * flw_vertexNormal;
    #endif

    #ifdef _FLW_GUEST_PREV_INSTANCES
    _flw_guestMotion(previousPass ? previousPos : flw_vertexPos);
    #elif defined(_FLW_GUEST_VELOCITY)
    _flw_guestMotion(flw_vertexPos);
    #endif

    flw_vertexNormal = normalize(flw_vertexNormal);
    #ifdef _FLW_GUEST_VERTEX_LIGHT
    _flw_guestVertexLight(_flw_drawPackedMaterial.y);
    #endif
    #ifdef _FLW_GUEST_EMISSIVE_PEAK
    flw_vertexColor.rgb /= max(max(flw_vertexColor.r, flw_vertexColor.g), max(flw_vertexColor.b, 1.0 / 255.0));
    #endif
    #if !defined(_FLW_GUEST_MESH_LIGHT) && !defined(_FLW_CRUMBLING)
    if ((_flw_drawPackedMaterial.y & _FLW_USE_LIGHT_MASK) == 0u) {
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
    _flw_packedMaterial = _flw_drawPackedMaterial;
    #endif
    #ifdef _FLW_GUEST_OIT
    _flw_oitViewZ = (flw_view * flw_vertexPos).z;
    #endif
}
