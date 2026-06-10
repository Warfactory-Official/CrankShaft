void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    // The billboard rotation is camera-dependent, so take the rotation-invariant bound about the anchor.
    radius = (length(center) + radius) * i.size;
    center = i.position;
}
