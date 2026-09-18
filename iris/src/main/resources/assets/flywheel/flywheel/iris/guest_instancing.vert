#ifdef FLW_EMBEDDED
layout(std140) uniform _FlwEmbed {
    mat4 _flw_embedPose;
    mat4 _flw_embedNormal;
};
#endif

void _flw_guestVertex() {
    #ifdef _FLW_CRUMBLING
    FlwInstance instance = _flw_unpackInstance(gl_BaseInstanceARB + gl_InstanceID);
    #else
    FlwInstance instance = _flw_unpackInstance(gl_InstanceID);
    #endif

    _flw_drawTag = _flw_drawPackedMaterial.x;
    _flw_itemTag = _flw_drawItemTag;
    #ifdef FLW_EMBEDDED
    _flw_modelMatrix = _flw_embedPose;
    _flw_normalMatrix = mat3(_flw_embedNormal);
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

    flw_vertexPos = vec4(_flw_aPosition, 1.0);
    flw_vertexColor = _flw_aColor;
    flw_vertexTexCoord = _flw_aTexCoord;
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = (vec2(_flw_aLight) + 8.0) / 256.0;
    flw_vertexNormal = _flw_aNormal;

    flw_instanceVertex(instance);
    flw_materialVertex();

    #ifdef FLW_EMBEDDED
    flw_vertexPos = _flw_modelMatrix * flw_vertexPos;
    flw_vertexNormal = _flw_normalMatrix * flw_vertexNormal;
    #endif

    flw_vertexNormal = normalize(flw_vertexNormal);
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
