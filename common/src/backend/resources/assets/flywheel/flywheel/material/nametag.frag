void flw_materialFragment() {
    // R8 glyph sheets carry coverage in R; rebuild as tinted coverage: alpha = a * coverage.
    flw_fragColor = vec4(flw_vertexColor.rgb, flw_vertexColor.a * flw_sampleColor.r);
}
