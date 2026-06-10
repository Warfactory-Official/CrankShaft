void flw_instanceVertex(in FlwInstance i) {
    // Camera-plane billboard: transpose(mat3(flw_view)) is the inverse view rotation, identical to
    // vanilla's rotY(180 - yaw) * rotX(-pitch) sprite composition (mod 360).
    mat3 billboard = transpose(mat3(flw_view));
    flw_vertexPos.xyz = i.position + billboard * (flw_vertexPos.xyz * i.size);
    flw_vertexNormal = billboard * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Half-texel offset to match vanilla's lightmap UV (see vertex_input.vert comment).
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    // Atlas sub-rect remap: base UV (mesh, full-texture-relative) into the per-instance region.
    flw_vertexTexCoord = i.uvRegion.xy + flw_vertexTexCoord * i.uvRegion.zw;
}
