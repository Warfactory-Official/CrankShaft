// CrankShaft divergence from upstream Flywheel — second of two glint passes mirroring
// vanilla 1.12.2 RenderItem.renderEffect. This file is vanilla pass 2 (the +10° pass);
// glint2.vert is vanilla pass 1 (the -50° pass).
void flw_materialVertex() {
    float p = flw_glintSpeedOption * flw_systemSeconds * 8.;

    flw_vertexTexCoord *= 8.;
    // Rotate by +10° around Z (vanilla pass 2). GLSL `vec *= mat` is `vec = vec * mat`, which
    // is `mat^T * vec` for column-vector semantics — so the mat2 below stores R(-10°) and the
    // implicit transpose lands us on R(+10°). Off-diagonal signs are the opposite of what you
    // get by reading the matrix entries straight.
    flw_vertexTexCoord *= mat2(0.98480775, -0.17364817, 0.17364817, 0.98480775);
    // X-only scroll, period 4873ms at glintSpeedOption=1.0.
    flw_vertexTexCoord += vec2(-p / 38.984, 0.);

    flw_vertexColor = vec4(0.501960784, 0.250980392, 0.800000000, 1.0);
}
