#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2; // lightmap

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out float flw_oitViewZ;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    flw_oitViewZ = viewPos.z;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
}
