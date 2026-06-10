void flw_instanceVertex(in FlwInstance i) {
    // Vanilla RenderLiving.renderLeash, evaluated per vertex: the mesh bakes the segment fraction
    // in UV.x, the ribbon side offsets in the position, and the parity colors in the vertex color.
    // Scale is 0 on seeded slots, collapsing them to a point at the anchor.
    float f = flw_vertexTexCoord.x;
    vec3 rope = vec3(i.delta.x * f,
            i.delta.y * (f * f + f) * 0.5 + (1.0 - f) * (4.0 / 3.0) + 0.125,
            i.delta.z * f);
    flw_vertexPos.xyz = i.start + (rope + flw_vertexPos.xyz) * i.scale;
    // Sample the center of the 1x1 white texture; the rope is vertex color x lightmap only.
    flw_vertexTexCoord = vec2(0.5);
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
}
