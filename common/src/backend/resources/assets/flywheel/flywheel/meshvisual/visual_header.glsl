vec4 flw_vertexPos;
vec3 flw_vertexNormal;
vec4 flw_vertexColor;
vec2 flw_vertexTexCoord;
ivec2 flw_vertexOverlay;
vec2 flw_vertexLight;

vec2 _flw_clipData;

layout(std430, binding = _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING) restrict readonly buffer _FlwInstanceIndexBuffer {
    uint _flw_instanceIndices[];
};
layout(std430, binding = _FLW_DRAW_BUFFER_BINDING) restrict readonly buffer _FlwDrawBuffer {
    MeshDrawCommand _flw_drawCommands[];
};
layout(std430, binding = 13) restrict readonly buffer _FlwMeshVertexBuffer {
    uint _flw_meshVertices[];
};
layout(std430, binding = 14) restrict readonly buffer _FlwMeshIndexBuffer {
    uint _flw_meshIndices[];
};
layout(std430, binding = _FLW_MATRIX_BUFFER_BINDING) restrict readonly buffer _FlwMatrixBuffer {
    Matrices _flw_matrices[];
};

uniform uint _flw_baseDraw;

// Render-origin-space modelview (no bob; 26.2 bobs PROJECTION) + systemSeconds/glint* mirrors (#defined by assembly).
layout(std140, binding = 9) uniform _FlwMeshVisualFrame {
    mat4 _flw_mvModelView;
    float _flw_mvSystemSeconds;
    float _flw_mvGlintSpeed;
    float _flw_mvGlintStrength;
};

// Live bob-included ProjMat (26.2 bobs the projection, not the camera): clip with this to bob exactly like MDI.
layout(std140, binding = 7) uniform Projection {
    mat4 ProjMat;
};

// Pooled vertex STRIDE 36: pos f32x3 @0, color rgba8 @12, uv @16, overlay s16x2 @24, light u16x2 @28, normal s8x3 @32.
void _flw_loadVertex(uint vi) {
    uint b = vi * 9u;
    flw_vertexPos = vec4(uintBitsToFloat(_flw_meshVertices[b + 0u]),
                         uintBitsToFloat(_flw_meshVertices[b + 1u]),
                         uintBitsToFloat(_flw_meshVertices[b + 2u]), 1.0);

    uint c = _flw_meshVertices[b + 3u];
    flw_vertexColor = vec4(float(c & 0xFFu), float((c >> 8u) & 0xFFu),
                           float((c >> 16u) & 0xFFu), float((c >> 24u) & 0xFFu)) / 255.0;

    flw_vertexTexCoord = vec2(uintBitsToFloat(_flw_meshVertices[b + 4u]),
                              uintBitsToFloat(_flw_meshVertices[b + 5u]));

    uint o = _flw_meshVertices[b + 6u];
    flw_vertexOverlay = ivec2(int(o << 16u) >> 16, int(o) >> 16);

    uint l = _flw_meshVertices[b + 7u];
    // Half-texel offset to match vanilla's lightmap UV (renderpass/header.vsh: (UV2 + 8) / 256).
    flw_vertexLight = (vec2(float(l & 0xFFFFu), float(l >> 16u)) + 8.0) / 256.0;

    uint n = _flw_meshVertices[b + 8u];
    flw_vertexNormal = vec3(float(int(n << 24u) >> 24), float(int(n << 16u) >> 24),
                            float(int(n << 8u) >> 24)) / 127.0;
}
