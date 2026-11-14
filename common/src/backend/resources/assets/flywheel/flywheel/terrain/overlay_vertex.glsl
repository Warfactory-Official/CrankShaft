#undef main

layout(std140) uniform TerrainOverlayFrame {
    mat4 TerrainClipToRelative;
};

#if defined(_FLW_TERRAIN_EXPLICIT_VARYINGS) || defined(_FLW_VK)
layout(location = 6) out vec3 _flw_terrainPosition;
layout(location = 7) flat out uint _flw_terrainReceiver;
#else
out vec3 _flw_terrainPosition;
flat out uint _flw_terrainReceiver;
#endif
#if defined(_FLW_VK_BDA) || defined(_FLW_TRANSLUCENT_INSTANCED)
layout(buffer_reference, std430, buffer_reference_align = 4) restrict readonly buffer _FlwReceiverRef {
    uint count;
    uint ids[];
};
#else
uniform usamplerBuffer TerrainReceiverIds;
#endif

void main() {
    _flw_terrainVertexBase();
    #ifdef _FLW_TERRAIN_BASE_POSITION
    _flw_terrainPosition = _flw_terrainBasePosition;
    #else
    vec4 position = TerrainClipToRelative * gl_Position;
    _flw_terrainPosition = position.xyz / position.w;
    #endif
    _flw_terrainReceiver = 0u;
    #if defined(_FLW_VK_BDA) || defined(_FLW_TRANSLUCENT_INSTANCED)
    #ifdef _FLW_TRANSLUCENT_INSTANCED
    uint drawBase = uint(gl_InstanceIndex) * 8u;
    uvec2 address = uvec2(_flw_translucentDraw[drawBase + 6u], _flw_translucentDraw[drawBase + 7u]);
    #else
    uvec2 address = _flw_drawData[gl_InstanceIndex]._ddPad;
    #endif
    if (any(notEqual(address, uvec2(0u)))) {
        _FlwReceiverRef receivers = _FlwReceiverRef(address);
        if (uint(gl_VertexIndex) < receivers.count) _flw_terrainReceiver = receivers.ids[gl_VertexIndex];
    }
    #else
    if (gl_VertexID + 1 < textureSize(TerrainReceiverIds)) _flw_terrainReceiver = texelFetch(TerrainReceiverIds, gl_VertexID + 1).r;
    #endif
}
