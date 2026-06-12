void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    radius = (length(center) + radius) * i.size;
    center = i.position;
}
