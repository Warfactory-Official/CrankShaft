void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    center = i.start + i.delta * (0.5 * i.scale);
    radius = (length(i.delta) * 0.5 + 1.5) * i.scale;
}
