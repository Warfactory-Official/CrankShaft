#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1; // overlay
#ifndef _FLW_BER_EMISSIVE
uniform sampler2D Sampler2; // lightmap
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef _FLW_BER_PER_FACE
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
#ifndef _FLW_BER_EMISSIVE
out vec4 lightMapColor;
#endif
out vec4 overlayColor;
out vec2 texCoord0;
// Eye-space (view) Z, perspective-correct; eye-linear depth = -flw_oitViewZ.
out float flw_oitViewZ;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    flw_oitViewZ = viewPos.z;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef _FLW_BER_PER_FACE
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif

#ifndef _FLW_BER_EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, UV2);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
