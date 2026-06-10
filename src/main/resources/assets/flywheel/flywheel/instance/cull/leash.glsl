void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    // Procedural rope: bound both endpoints plus hang/sag slack; the mesh sphere is meaningless.
    center = i.start + i.delta * (0.5 * i.scale);
    radius = (length(i.delta) * 0.5 + 1.5) * i.scale;
}
