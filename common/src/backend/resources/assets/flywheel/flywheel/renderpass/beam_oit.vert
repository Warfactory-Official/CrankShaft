#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec4 vertexColor;
out vec2 texCoord0;
// Eye-space (view) Z, perspective-correct; eye-linear depth = -flw_oitViewZ.
out float flw_oitViewZ;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    flw_oitViewZ = viewPos.z;

    vertexColor = Color;
    texCoord0 = UV0;
}
