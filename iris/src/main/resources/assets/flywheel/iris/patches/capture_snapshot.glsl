layout(std430, binding = _FLW_SNAPSHOT_BINDING) readonly buffer FlwCaptureDepth { float flw_captureDepth[]; };

// Depth at this frame's capture seam: opaque plus every forward depth writer drawn before it, none of the captured
// surfaces.
float flw_captureLimit(ivec2 pixel, float preTranslucent) {
    // Raw reverse-Z (Iris inverts only the depth reads it patches); the frame's clear zeroes it, the far plane, so a
    // capture before the seam keeps the pre-translucent copy.
    float snapshot = flw_captureDepth[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)];
    return _FLW_REVERSED_DEPTH ? max(snapshot, preTranslucent) : min(1.0 - snapshot, preTranslucent);
}
