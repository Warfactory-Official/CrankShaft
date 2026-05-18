// CrankShaft addition: one-sided half-space clip in OBJ-local space. Discards anywhere
// dot(plane.xyz, slidPos) > plane.w. Reads varyings declared in api_impl.frag — see
// clip_slab.glsl for the rationale on lifting them there.

bool flw_discardPredicate(vec4 color) {
    if (dot(_flw_clipPlane.xyz, _flw_clipSlidPos) > _flw_clipPlane.w) {
        return true;
    }
    return color.a < 0.5;
}
