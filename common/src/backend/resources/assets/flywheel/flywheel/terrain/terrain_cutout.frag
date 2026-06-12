#moj_import <minecraft:fog.glsl>

#ifdef _FLW_MESH_PER_PRIMITIVE
layout(location = 0) in vec3 _flw_meshTint;
layout(location = 4) in vec2 _flw_meshFog;
layout(location = 13) perprimitiveEXT in float flw_chunkVisibility;
#else
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in float flw_chunkVisibility; // per-section chunk-load fade (vanilla ChunkVisibility), from the resident vis buffer
#endif
in vec2 texCoord0;

uniform sampler2D Sampler0; // block atlas

out vec4 fragColor;

void main() {
#ifdef _FLW_MESH_PER_PRIMITIVE
    // Locals, not #defines: fog.glsl's apply_fog PARAMETERS reuse these names, so a macro would mangle them.
    vec4 vertexColor = vec4(_flw_meshTint, 1.0);
    float sphericalVertexDistance = _flw_meshFog.x;
    float cylindricalVertexDistance = _flw_meshFog.y;
#endif
    vec4 color = flw_sampleAtlas(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.5) {
        discard;
    }
    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, flw_chunkVisibility);
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    fragColor = color;
}
