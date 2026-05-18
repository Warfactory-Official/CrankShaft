// CrankShaft addition: two-sided slab clip in OBJ-local space. Discards anywhere
// |dot(plane.xyz, slidPos)| > plane.w. Reads varyings declared in api_impl.frag — those are
// always present so this file can be merged into the uber-cutout component without breaking
// link on TRANSFORMED-based pipelines. Meaningful values only come from CLIP_TRANSFORMED
// (instance/clip_transformed.vert writes them); other pipelines see zero, which is a
// degenerate plane that discards nothing. Falls back to the half-alpha cutout afterwards
// so transparent texels in the panel art still drop.

bool flw_discardPredicate(vec4 color) {
    if (abs(dot(_flw_clipPlane.xyz, _flw_clipSlidPos)) > _flw_clipPlane.w) {
        return true;
    }
    return color.a < 0.5;
}
