void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    center = i.anchor;
    radius = (abs(i.offset.x) + abs(i.offset.y) + i.size.x + i.size.y) * 0.025;
}
