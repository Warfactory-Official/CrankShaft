// VK twin of visual_header.glsl: geometry by BDA push constant (GL's SSBO 13/14); Projection at slot 16, frame at 9.
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
layout(std430, binding = _FLW_MATRIX_BUFFER_BINDING) restrict readonly buffer _FlwMatrixBuffer {
    Matrices _flw_matrices[];
};

layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer _FlwMeshVertsRef {
    uint w[];
};
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer _FlwMeshIndicesRef {
    uint w[];
};

layout(push_constant) uniform _FlwMeshVisualPush {
    uint64_t _flw_vertsAddr;
    uint64_t _flw_indicesAddr;
    uint64_t _flw_boundsAddr;
    uint _flw_baseDraw;
    uint _flw_crumblingInstance;       // object uint offset (instancer.local2ObjectUintOffset)
    uint _flw_crumblingFirstIndex;
    uint _flw_crumblingVertexOffset;
    uint _flw_crumblingTriCount;
    uint _flw_crumblingPackedMaterial;
};

layout(std140, binding = 9) uniform _FlwMeshVisualFrame {
    mat4 _flw_mvModelView;
    float _flw_mvSystemSeconds;
    float _flw_mvGlintSpeed;
    float _flw_mvGlintStrength;
};

layout(std140, binding = 16) uniform Projection {
    mat4 ProjMat;
};

void _flw_loadVertex(uint vi) {
    _FlwMeshVertsRef verts = _FlwMeshVertsRef(_flw_vertsAddr);
    uint b = vi * 9u;
    flw_vertexPos = vec4(uintBitsToFloat(verts.w[b + 0u]),
                         uintBitsToFloat(verts.w[b + 1u]),
                         uintBitsToFloat(verts.w[b + 2u]), 1.0);

    uint c = verts.w[b + 3u];
    flw_vertexColor = vec4(float(c & 0xFFu), float((c >> 8u) & 0xFFu),
                           float((c >> 16u) & 0xFFu), float((c >> 24u) & 0xFFu)) / 255.0;

    flw_vertexTexCoord = vec2(uintBitsToFloat(verts.w[b + 4u]),
                              uintBitsToFloat(verts.w[b + 5u]));

    uint o = verts.w[b + 6u];
    flw_vertexOverlay = ivec2(int(o << 16u) >> 16, int(o) >> 16);

    uint l = verts.w[b + 7u];
    // Half-texel offset to match vanilla's lightmap UV (renderpass/header.vsh: (UV2 + 8) / 256).
    flw_vertexLight = (vec2(float(l & 0xFFFFu), float(l >> 16u)) + 8.0) / 256.0;

    uint n = verts.w[b + 8u];
    flw_vertexNormal = vec3(float(int(n << 24u) >> 24), float(int(n << 16u) >> 24),
                            float(int(n << 8u) >> 24)) / 127.0;
}

uint _flw_meshIndex(uint i) {
    return _FlwMeshIndicesRef(_flw_indicesAddr).w[i];
}
