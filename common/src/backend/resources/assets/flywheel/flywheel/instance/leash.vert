void flw_instanceVertex(in FlwInstance i) {
    // 26.2: leash rope -- x/z linear to holder, y sags sign-dependently; scale 0 collapses unposed slots.
    float p = flw_vertexTexCoord.x;
    float dy = i.delta.y;
    float ropeY = dy > 0.0 ? dy * p * p : dy - dy * (1.0 - p) * (1.0 - p);
    vec3 rope = vec3(i.delta.x * p, ropeY, i.delta.z * p);
    flw_vertexPos.xyz = i.start + (rope + flw_vertexPos.xyz) * i.scale;
    flw_vertexTexCoord = vec2(0.5);
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
}
