#ifndef _FLW_AO_STRENGTH
#define _FLW_AO_STRENGTH 1.0
#endif
#ifndef _FLW_AO_OPTION_ENABLED
#define _FLW_AO_OPTION_ENABLED true
#endif
#ifndef _FLW_CARDINAL_ENABLED
#define _FLW_CARDINAL_ENABLED true
#endif

float _flw_appliedAo = 1.0;
float _flw_appliedCardinal = 1.0;

bool flw_aoEnabled() {
    return flw_material.ambientOcclusion && _FLW_AO_OPTION_ENABLED && _FLW_AO_STRENGTH > 0.0;
}

void flw_applyAo(float rawAo) {
    float factor = mix(1.0, rawAo, _FLW_AO_STRENGTH);
    _flw_appliedAo *= factor;
    flw_fragColor.rgb *= factor;
}

void flw_applyCardinal(float factor) {
    if (_FLW_CARDINAL_ENABLED) {
        _flw_appliedCardinal *= factor;
        flw_fragColor.rgb *= factor;
    }
}
