#ifdef FLW_FORWARD_ITERATION
bool _flw_forwardIterationIsGlass(vec4 packedData) {
    float material = Unpack2xU8_ID_from_U16(packedData.z).y;
    float emission = Unpack2xU8_from_U16(packedData.w).x;
    return material == MATID_STAINEDGLASS && emission == 0.0;
}

vec4 _flw_forwardIterationGlass(vec3 viewPosition, vec3 normal, vec4 packedData) {
    vec4 albedo = vec4(Unpack2xU8_from_U16(packedData.x), Unpack2xU8_from_U16(packedData.y));
    vec3 transmission = AlbedoToAbsorption(GammaToLinear(albedo.rgb), albedo.a);
    vec3 view = _flw_forwardView(viewPosition);
    float fresnel = _flw_forwardFresnel(view, normal);
    return _flw_forwardWaterTransport(transmission, vec3(0.0),
            _flw_forwardWaterReflection(viewPosition, view, normal), fresnel);
}
#endif
