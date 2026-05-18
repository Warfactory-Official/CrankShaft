#include "flywheel:internal/material.glsl"
#include "flywheel:internal/api_impl.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"

// Required for the glint pass to align with the cutout pass under depthTest=EQUAL/LEQUAL.
// Cutout and glint compile into separate uber-shader programs (different flw_materialVertex
// bodies) — without invariance the compiler is free to reorder arithmetic, producing
// 1-ULP depth differences that make >50% of glint fragments fail the depth equality test
// (confirmed via Nsight: "More than half samples failed depth test" on the GLINT draw).
invariant gl_Position;

out vec4 flw_vertexPos;
out vec4 flw_vertexColor;
out vec2 flw_vertexTexCoord;
flat out ivec2 flw_vertexOverlay;
out vec2 flw_vertexLight;
out vec3 flw_vertexNormal;

// CrankShaft addition: clip-shader varyings. Declared unconditionally so every linked program has
// vertex-side outs matching the fragment-side ins added to api_impl.frag — the cutout
// shaders are merged into an uber-component that references these in every program
// (regardless of which cutout actually fires at runtime), so TRANSFORMED-based pipelines
// were failing to link with "_flw_clipPlane not declared as input from previous stage".
// instance/clip_transformed.vert writes meaningful values inside flw_instanceVertex; other
// instance vertex shaders leave them at the default zero (matches a degenerate plane that
// the slab/halfspace tests accept everywhere, so the wrong-pair case discards nothing).
out vec3 _flw_clipSlidPos;
flat out vec4 _flw_clipPlane;

FlwMaterial flw_material;

uint flw_vertexId;
