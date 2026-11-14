#undef main

void main() {
    #if defined(_FLW_OIT_EMISSION) && !defined(OIT_ADDITIVE)
    discard;
    #elif defined(_FLW_EVALUATE) && !defined(_FLW_OIT_EMISSION) && defined(OIT_ADDITIVE)
    discard;
    #else
    #ifdef _FLW_TERRAIN_OVERLAY
    _flw_terrainPrepare();
    #endif
    _flw_nativeMain();
    #if defined(_FLW_TERRAIN_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    fragColor = _flw_terrainFinish(fragColor);
    #endif
    #ifdef _FLW_OIT_INSERT
    // Native fog follows sampleColorForAccumulation; insert only the completed premultiplied color.
    _flw_mlabInsertPremul(fragColor.rgb, fragColor.a, gl_FragCoord.z);
    #endif
    #endif
}
