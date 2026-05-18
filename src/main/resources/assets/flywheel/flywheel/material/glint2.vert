// CrankShaft divergence from upstream Flywheel — second glint pass.
//
// Upstream Flywheel renders the glint mesh once with rotation +10° and scroll vec2(-p/110, p/30).
// Vanilla 1.12.2 (RenderItem.renderEffect) renders the glint mesh TWICE per item, with
// different rotations and scroll periods, so the two passes overlap to produce the iridescent
// moiré-like shimmer that defines enchanted-item visuals on this version.
//
// glint.vert  → vanilla pass 2: rotation +10° around Z, X-only scroll with period 4873ms.
// glint2.vert → vanilla pass 1: rotation -50° around Z, X-only scroll with period 3000ms.
// ItemModels appends a ConfiguredMesh using Materials.GLINT_2 after Materials.GLINT so foil
// items get both layers.
//
// Vanilla pass 1 in fixed-function texture-matrix form:
//   scale(8); translate(f, 0, 0); rotate(-50°, Z); where f = (systemTime % 3000ms) / 3000 / 8
//   → final_uv = 8 * R(-50°) * uv + (8f, 0) = 8 * R * uv + (X, 0), X ∈ [0, 1).
//
// Translating to our shader, with p = flw_glintSpeedOption * flw_systemSeconds * 8:
//   X scroll per second = 8 * (1/3) = 8/3, so X coefficient = p / (8 * 3) = p / 24.
// Y scroll: 0 (vanilla never translates Y on this pass).
//
// GLSL `vec *= mat` is `vec = vec * mat`, which equals `mat^T * vec` for column-vector
// semantics — so to apply R(-50°) to uv via this op, the mat2 must store R(+50°) and the
// implicit transpose lands us on R(-50°). Off-diagonal signs below are the opposite of what
// you get by reading the matrix entries straight.
//   R(+50°) = [ 0.6427876  -0.7660444; 0.7660444  0.6427876 ]
//   GLSL mat2 (column-major): mat2(0.6427876, 0.7660444, -0.7660444, 0.6427876).
void flw_materialVertex() {
    float p = flw_glintSpeedOption * flw_systemSeconds * 8.;

    flw_vertexTexCoord *= 8.;
    // Rotate by -50° around Z (vanilla pass 1 rotation). See header comment for the transpose.
    flw_vertexTexCoord *= mat2(0.6427876, 0.7660444, -0.7660444, 0.6427876);
    // X-only scroll, period 3000ms at glintSpeedOption=1.0.
    flw_vertexTexCoord += vec2(p / 24., 0.);

    // Same vanilla tint as the first pass (0xFF8040CC).
    flw_vertexColor = vec4(0.501960784, 0.250980392, 0.800000000, 1.0);
}
