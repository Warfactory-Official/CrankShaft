// Embedded: the composed pose/normal ride a per-draw UBO (the RenderPass RHI has no matrix-uniform setter).

#ifdef FLW_EMBEDDED
layout(std140) uniform _FlwEmbed {
    mat4 _flw_embedPose;
    mat4 _flw_embedNormal;
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
    flw_vertexLight = (vec2(UV2) + 8.0) / 256.0;
    flw_vertexNormal = Normal;
}

void main() {
    // Crumbling: ONE instance (the broken block); BaseInstance+InstanceID are int, not uint.
    #ifdef _FLW_CRUMBLING
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
    lightCoord = flw_vertexLight;
    overlayCoord = flw_vertexOverlay;
    #ifdef _FLW_DEBUG
    #ifdef _FLW_CRUMBLING
    _flw_ids = uvec2(uint(gl_BaseInstanceARB + gl_InstanceID), uint(gl_BaseVertex));
    #else
    _flw_ids = uvec2(uint(gl_InstanceID), uint(gl_BaseVertex));
    #endif
    #endif

    sphericalVertexDistance = fog_spherical_distance(viewPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPos.xyz);
}
