// CrankShaft addition: TransformedInstance + per-instance slide and clip plane. Applies the slide
// to OBJ-local Position before pose so the panel visibly moves; writes the slid OBJ-local
// position and plane into the _flw_clipSlidPos / _flw_clipPlane varyings declared in
// api_impl.vert (those are always-on so non-clip pipelines can still link).
//
// Mirrors `instance/transformed.vert` plus the slide step + varying assignment. The pose
// matrix on clip parts is just the door's staticBase (frame anchor) — the per-part panel
// motion is in `slide`, NOT in pose, so the clip plane stays fixed in OBJ-local space and
// the slid geometry crosses it as the door opens.

void flw_instanceVertex(in FlwInstance i) {
    vec3 slidPos = flw_vertexPos.xyz + i.slide;
    flw_vertexPos = i.pose * vec4(slidPos, flw_vertexPos.w);
    flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Half-texel offset to match vanilla's lightmap UV (see vertex_input.vert comment).
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
    _flw_clipSlidPos = slidPos;
    _flw_clipPlane = i.plane;
}
