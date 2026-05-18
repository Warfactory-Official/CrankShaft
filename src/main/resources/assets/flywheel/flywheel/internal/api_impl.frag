#include "flywheel:internal/material.glsl"
#include "flywheel:internal/api_impl.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"

in vec4 flw_vertexPos;
in vec4 flw_vertexColor;
in vec2 flw_vertexTexCoord;
flat in ivec2 flw_vertexOverlay;
in vec2 flw_vertexLight;
in vec3 flw_vertexNormal;

// flw_distance is now computed per-fragment in common.frag from the interpolated flw_vertexPos
// to avoid the convexity error of linearly interpolating length() across long line edges
// (visible as a "fogged middle, bright corners" artifact on the LightStorage debug overlay).
float flw_distance;

// CrankShaft addition: clip-shader varyings, declared unconditionally to match the corresponding
// out declarations in api_impl.vert. See that file for the rationale.
in vec3 _flw_clipSlidPos;
flat in vec4 _flw_clipPlane;

vec4 flw_sampleColor;

FlwMaterial flw_material;

bool flw_fragDiffuse;
vec4 flw_fragColor;
ivec2 flw_fragOverlay;
vec2 flw_fragLight;

uniform sampler2D flw_diffuseTex;
uniform sampler2D flw_overlayTex;
uniform sampler2D flw_lightTex;
