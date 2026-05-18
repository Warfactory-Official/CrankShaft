void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    flw_vertexNormal = i.normal * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Some drivers have a bug where uint over float division is invalid, so use an explicit cast.
    // Half-texel offset to match vanilla's lightmap UV (see vertex_input.vert comment).
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
}
