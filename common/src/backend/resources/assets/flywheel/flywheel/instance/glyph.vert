void flw_instanceVertex(in FlwInstance i) {
    vec2 fontPos = i.offset + flw_vertexPos.xy * i.size;
    fontPos.x += i.shear * (1.0 - 2.0 * flw_vertexPos.y);
    mat3 billboard = transpose(mat3(flw_view));
    flw_vertexPos.xyz = i.anchor + billboard * vec3(fontPos.x * 0.025, fontPos.y * -0.025, i.depth * 0.025);
    flw_vertexColor *= i.color;
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    flw_vertexTexCoord = i.uvRegion.xy + flw_vertexTexCoord * i.uvRegion.zw;
}
