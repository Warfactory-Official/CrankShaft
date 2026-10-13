#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #moj_import <minecraft:chunksection.glsl>
#endif
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
in ivec3 ChunkPosition;
in float ChunkVisibility;
#endif

uniform sampler2D Sampler2; // lightmap

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
// Eye-space Z, perspective-correct, so the fragment gets true eye-linear depth (= -flw_oitViewZ).
out float flw_oitViewZ;
out float flw_chunkVisibility;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    vec4 viewPos = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * viewPos;
    flw_oitViewZ = viewPos.z;

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;

    #ifdef _FLW_CHUNK_VISIBLE_RANGE
    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    flw_chunkVisibility = mix(1.0, ChunkVisibility,
            clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    #else
    flw_chunkVisibility = ChunkVisibility;
    #endif
}
