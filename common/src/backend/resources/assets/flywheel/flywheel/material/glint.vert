void flw_materialVertex() {
    float p = flw_glintSpeedOption * flw_systemSeconds * 8.;

    flw_vertexTexCoord *= 8.;
    // 26.2: columns transposed vs upstream -- v*M row-multiply rotates +10 deg, matching vanilla's M*v TextureMat.
    flw_vertexTexCoord *= mat2(0.98480775, -0.17364817, 0.17364817, 0.98480775);
    flw_vertexTexCoord += vec2(-p / 110., p / 30.);

    // 26.2: apply the Glint Strength accessibility option (vanilla's glint fragment multiplies by GlintAlpha,
    // default 0.75); upstream Flywheel declares flw_glintStrengthOption but never consumes it. No draw stage has
    // the flywheel options UBO -- the value rides _FlwInstanceDraw (RenderPass paths) / _FlwMeshVisualFrame
    // (mesh tiers, via #define) alongside the speed option.
    flw_vertexColor.rgb *= flw_glintStrengthOption;
}
