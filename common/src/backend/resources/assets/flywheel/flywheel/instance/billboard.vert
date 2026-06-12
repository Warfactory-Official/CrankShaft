void flw_instanceVertex(in FlwInstance i) {
    mat3 billboard = transpose(mat3(flw_view));
    flw_vertexPos.xyz = i.position + billboard * (flw_vertexPos.xyz * i.size);
    flw_vertexNormal = billboard * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    flw_vertexTexCoord = i.uvRegion.xy + flw_vertexTexCoord * i.uvRegion.zw;
}
