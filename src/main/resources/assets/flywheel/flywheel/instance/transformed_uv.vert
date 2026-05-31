void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Half-texel offset to match vanilla's lightmap UV (see vertex_input.vert comment).
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    // Atlas sub-rect remap: base UV (mesh, full-texture-relative) into the per-instance region.
    flw_vertexTexCoord = i.uvRegion.xy + flw_vertexTexCoord * i.uvRegion.zw;
}
