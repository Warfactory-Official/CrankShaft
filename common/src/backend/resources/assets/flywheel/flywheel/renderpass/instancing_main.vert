// Embedded: the composed pose/normal ride a per-draw UBO (the RenderPass RHI has no matrix-uniform setter).

#ifdef FLW_EMBEDDED
layout(std140) uniform _FlwEmbed {
    mat4 _flw_embedPose;
    mat4 _flw_embedNormal;
};
#endif

#ifdef _FLW_INSTANCING_SELECTOR
layout(std140) uniform _FlwInstanceSelector {
    ivec4 _flw_instanceSelector;
};
#endif

#ifdef _FLW_DEBUG
flat out uvec2 _flw_ids;
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
    #ifdef _FLW_INSTANCING_SELECTOR
    FlwInstance instance = _flw_unpackInstance(_flw_instanceSelector.x + gl_InstanceID);
    #elif defined(_FLW_CRUMBLING)
    FlwInstance instance = _flw_unpackInstance(gl_BaseInstanceARB + gl_InstanceID);
    #else
    FlwInstance instance = _flw_unpackInstance(gl_InstanceID);
    #endif

    _flw_layoutVertex();
    flw_instanceVertex(instance);
    flw_materialVertex();

    #ifdef FLW_EMBEDDED
    _flw_modelMatrix = _flw_embedPose;
    _flw_normalMatrix = mat3(_flw_embedNormal);
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

    _flw_packedMaterial = _flw_drawPackedMaterial;
    vertexColor = flw_vertexColor;
    texCoord0 = flw_vertexTexCoord;
    lightCoord = vec2(max(flw_vertexLight.x, _flw_dynamicBlockLight(flw_vertexPos.xyz) / 16.0), flw_vertexLight.y);
    overlayCoord = flw_vertexOverlay;
    #ifdef _FLW_DEBUG
    #ifdef _FLW_INSTANCING_SELECTOR
    _flw_ids = uvec2(uint(_flw_instanceSelector.y + _flw_instanceSelector.x + gl_InstanceID), uint(_flw_instanceSelector.z));
    #else
    #if __VERSION__ >= 460
    #define _FLW_BASE_VERTEX gl_BaseVertex
    #else
    #define _FLW_BASE_VERTEX gl_BaseVertexARB
    #endif
    #ifdef _FLW_CRUMBLING
    _flw_ids = uvec2(uint(gl_BaseInstanceARB + gl_InstanceID), uint(_FLW_BASE_VERTEX));
    #else
    _flw_ids = uvec2(uint(gl_InstanceID), uint(_FLW_BASE_VERTEX));
    #endif
    #endif
    #endif

    vec2 fogDistances = flw_fogDistances(viewPos.xyz, ModelViewMat);
    sphericalVertexDistance = fogDistances.x;
    cylindricalVertexDistance = fogDistances.y;
}
