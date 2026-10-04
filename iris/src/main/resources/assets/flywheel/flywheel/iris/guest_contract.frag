float _flw_diffuseFactor() {
    if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_ENTITY) {
        return diffuseFromLightDirections(gl_FrontFacing ? flw_vertexNormal : -flw_vertexNormal);
    } else if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_CHUNK) {
        return flw_constantAmbientLight == 1u ? diffuseNether(flw_vertexNormal) : diffuse(flw_vertexNormal);
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
    #ifdef _FLW_GUEST_OLD_LIGHTING
    return 1.0;
    #else
    return _flw_diffuseFactor();
    #endif
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
    _flw_unpackMaterialProperties(_flw_packedMaterial.y, flw_material);

    flw_sampleColor = sampleColor;
    flw_fragColor = sampleColor * flw_vertexColor;
    flw_fragLight = flw_vertexLight;
    flw_fragOverlay = flw_vertexOverlay;

    flw_materialFragment();

    #if defined(_FLW_GUEST_MAIN_OIT) && !defined(_FLW_GUEST_BORROWED_BLOCK)
    // Pack auxiliary outputs can carry opacity even when the model's coverage is zero.
    if (flw_fragColor.a <= 0.0) discard;
    #endif

    fragOverlay = vec4(0.0);
    if (flw_material.useOverlay) {
        fragOverlay = texelFetch(Sampler1, flw_fragOverlay, 0);
        fragOverlay.a = 1.0 - fragOverlay.a;
    }

    #ifdef _FLW_TRACKED_LIGHTING
    _flw_appliedAo = 1.0;
    _flw_appliedCardinal = 1.0;
    #else
    vec3 unlit = flw_fragColor.rgb;
    #endif
    flw_shaderLight();
    flw_fragLight.x = max(flw_fragLight.x, _flw_dynamicBlockLight(flw_vertexPos.xyz) * (1.0 / 16.0));
    #ifdef _FLW_GUEST_OLD_LIGHTING
    #ifdef _FLW_TRACKED_LIGHTING
    flw_applyCardinal(_flw_diffuseFactor());
    #else
    flw_fragColor.rgb *= _flw_diffuseFactor();
    #endif
    #endif

    ao = 1.0;
    if (flw_material.ambientOcclusion) {
        #ifdef _FLW_TRACKED_LIGHTING
        // Colorwheel returns shaded colour and its shading ratio, not clean albedo plus a second multiplier.
        ao = clamp(_flw_appliedAo * _flw_appliedCardinal, 0.0, 1.0);
        #else
        vec3 ratio = vec3(unlit.r == 0.0 ? 0.0 : flw_fragColor.r / unlit.r,
                          unlit.g == 0.0 ? 0.0 : flw_fragColor.g / unlit.g,
                          unlit.b == 0.0 ? 0.0 : flw_fragColor.b / unlit.b);
        ao = all(equal(unlit, vec3(0.0))) ? 1.0 : clamp(max(max(ratio.r, ratio.g), ratio.b), 0.0, 1.0);
        #endif
    }

    #ifdef _FLW_USE_DISCARD
    if (flw_discardPredicate(flw_fragColor)) {
        discard;
    }
    #endif

    fragColor = flw_fragColor;
    #ifdef _FLW_GUEST_ADDITIVE_ONE
    fragColor.a = 1.0;
    #endif
    // Contract light is texel-centred, as vanilla's sample_lightmap input.
    fragLight = flw_fragLight + 1.0 / 32.0;
}
