void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    // Port: pose is affine, so inverse(mat3(M)) == mat3(inverse(M)) -- bit-identical, 3x3 vs 4x4 inverse.
    flw_vertexNormal = transpose(inverse(mat3(i.pose))) * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    flw_vertexTexCoord = i.uvRegion.xy + flw_vertexTexCoord * i.uvRegion.zw;
}
