void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    // The element lies within |offset| + size font px of the anchor, at 0.025 world units per px;
    // the rotation is camera-dependent so take the rotation-invariant bound.
    center = i.anchor;
    radius = (abs(i.offset.x) + abs(i.offset.y) + i.size.x + i.size.y) * 0.025;
}
