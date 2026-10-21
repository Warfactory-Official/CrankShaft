float _flw_diffuseFactor() {
    vec3 normal = gl_FrontFacing ? flw_vertexNormal : -flw_vertexNormal;
    if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_ENTITY) {
        return diffuseFromLightDirections(normal);
    }
    if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_CHUNK) {
        return flw_constantAmbientLight == 1u ? diffuseNether(normal) : diffuse(normal);
    }
    return 1.0;
}

bool _flw_isUnlit() {
    return !flw_material.useLight;
}

bool _flw_borrowedBlock() {
    #ifdef _FLW_GUEST_BORROWED_BLOCK
    return true;
    #else
    return false;
    #endif
}

bool _flw_hasFog() {
    #ifdef _FLW_GUEST_FOG_NONE
    return false;
    #else
    return true;
    #endif
}

float _flw_unlitCardinalFactor() {
    return _flw_packOldLighting ? 1.0 : _flw_diffuseFactor();
}

float _flw_emissionCoverage() {
    #ifdef _FLW_GUEST_ADDITIVE_ONE
    return 1.0;
    #else
    return flw_sampleColor.a > 0.0 ? flw_fragColor.a / flw_sampleColor.a : 0.0;
    #endif
}

void clrwl_computeFragment(vec4 sampleColor, out vec4 fragColor, out vec2 fragLight, out float ao,
        out vec4 fragOverlay) {
    #ifdef _FLW_GUEST_CRUMBLING
    vec4 crumblingSampleColor = sampleColor;
    sampleColor = texture(flw_diffuseTex, flw_vertexTexCoord);
    #endif
    _flw_unpackMaterialProperties(_flw_packedMaterial.y, flw_material);
    _flw_appliedAo = 1.0;
    _flw_trackedAo = false;
    flw_sampleColor = sampleColor;
    flw_fragColor = sampleColor * flw_vertexColor;
    flw_fragOverlay = flw_vertexOverlay;
    flw_fragLight = flw_vertexLight;
    flw_fragDiffuse = flw_material.cardinalLightingMode != FLW_MAT_CARDINAL_LIGHTING_MODE_OFF;
    flw_distance = length(flw_vertexPos.xyz - flw_cameraPos);
    flw_materialFragment();
    #if defined(_FLW_GUEST_MAIN_OIT) && !defined(_FLW_GUEST_BORROWED_BLOCK)
    if (flw_fragColor.a <= 0.0) discard;
    #endif

    fragOverlay = vec4(0.0);
    if (flw_material.useOverlay) {
        fragOverlay = texelFetch(flw_overlayTex, flw_fragOverlay, 0);
        fragOverlay.a = 1.0 - fragOverlay.a;
    }

    vec3 unlit = flw_fragColor.rgb;
    flw_shaderLight();
    float cardinal = _flw_packOldLighting ? _flw_diffuseFactor() : 1.0;
    flw_fragColor.rgb *= cardinal;
    if (!flw_material.useLight) flw_fragLight = vec2(240.0 / 256.0);

    ao = 1.0;
    if (flw_material.ambientOcclusion) {
        vec3 ratio = vec3(unlit.r == 0.0 ? 0.0 : flw_fragColor.r / unlit.r,
                unlit.g == 0.0 ? 0.0 : flw_fragColor.g / unlit.g,
                unlit.b == 0.0 ? 0.0 : flw_fragColor.b / unlit.b);
        ao = _flw_trackedAo ? clamp(_flw_appliedAo * cardinal, 0.0, 1.0)
        : all(equal(unlit, vec3(0.0))) ? cardinal : clamp(max(max(ratio.r, ratio.g), ratio.b), 0.0, 1.0);
    }

    #ifdef _FLW_GUEST_CRUMBLING
    flw_fragColor.rgb = crumblingSampleColor.rgb;
    flw_fragColor.a *= crumblingSampleColor.a;
    #endif
    if (flw_discardPredicate(flw_fragColor)) discard;
    fragColor = flw_fragColor;
    #ifdef _FLW_GUEST_ADDITIVE_ONE
    fragColor.a = 1.0;
    #endif
    fragLight = flw_fragLight + 1.0 / 32.0;
}
