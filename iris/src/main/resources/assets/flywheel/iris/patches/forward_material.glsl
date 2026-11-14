vec4 _flw_forwardColor;
vec3 _flw_forwardLogTint;
vec3 _flw_forwardNativeSun;
vec3 _flw_forwardNativeSky;
bool _flw_forwardEvaluate;
bool _flw_forwardShadeMaterial;
bool _flw_forwardWaterSurface;
vec3 _flw_forwardWaterPosition;
vec3 _flw_forwardOpaquePosition;
float _flw_forwardWaterFresnel;
vec3 _flw_forwardWaterTransmission;
#ifdef FLW_FORWARD_SUNDIAL
GbufferData _flw_forwardWaterMaterial;
#endif
#ifdef FLW_FORWARD_ITERATION
float _flw_forwardAlpha;
float _flw_forwardTorchLight;
bool _flw_forwardIterationIsGlass(vec4 packedData);
vec4 _flw_forwardIterationGlass(vec3 position, vec3 normal, vec4 packedData);
#endif

vec3 _flw_forwardLinear(vec3 color) {
    #ifdef FLW_FORWARD_SEUS
    return TransformInputColor(GammaToLinear(max(color, vec3(0.0))));
    #else
    return pow(max(color, vec3(0.0)), vec3(2.2));
    #endif
}

#ifdef FLW_FORWARD_OVERWORLD
vec3 _flw_forwardShadowPosition(vec3 position) {
    #ifdef FLW_FORWARD_SUNDIAL
    return worldPosToShadowCoord(position);
    #else
    #ifdef FLW_FORWARD_SEUS
    #ifdef FLW_FORWARD_GFME
    return WorldPosToShadowProjPos(position);
    #else
    float distance;
    float distortion;
    return WorldPosToShadowProjPos(position, distance, distortion);
    #endif
    #else
    return DistortShadowScreenPos(ShadowScreenPos_From_WorldPos(position));
    #endif
    #endif
}
#endif

vec3 _flw_forwardShadow(vec3 position, vec3 normal) {
    #ifdef FLW_FORWARD_OVERWORLD
    vec3 coord = _flw_forwardShadowPosition(position + normal * 0.02);
    if (any(lessThan(coord, vec3(0.0))) || any(greaterThan(coord, vec3(1.0)))) return vec3(1.0);
    #ifdef FLW_FORWARD_REVERSED_DEPTH
    #ifdef FLW_FORWARD_ITERATION
    return vec3(step(textureLod(shadowtex0, coord.xy, 0.0).r, coord.z + 0.0002));
    #else
    return vec3(texture(shadowtex0, vec3(coord.xy, coord.z + 0.0002)));
    #endif
    #else
    #ifdef FLW_FORWARD_ITERATION
    return vec3(step(coord.z - 0.0002, textureLod(shadowtex0, coord.xy, 0.0).r));
    #else
    return vec3(texture(shadowtex0, vec3(coord.xy, coord.z - 0.0002)));
    #endif
    #endif
    #else
    return vec3(1.0);
    #endif
}

vec3 _flw_forwardAbsorption(float distance) {
    #ifdef FLW_FORWARD_SEUS
    return exp(-GetWaterAbsorption() * distance);
    #else
    return exp(-vec3(0.12, 0.055, 0.025) * distance);
    #endif
}

float _flw_forwardWaterThickness(vec3 position, out vec3 opaquePosition) {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(depthtex1, 0));
    #ifdef FLW_FORWARD_SEUS
    uv *= 2.0;
    #endif
    float depth = texelFetch(depthtex1, pixel, 0).r;
    #ifdef FLW_FORWARD_REVERSED_DEPTH
    // Raw reversed depth: sky is 0 and zero-to-one NDC z is the depth itself.
    if (depth == 0.0) {
    #else
    if (depth == 1.0) {
    #endif
        opaquePosition = normalize(position) * 1e6;
        return 1e6;
    }
    #ifdef FLW_FORWARD_REVERSED_DEPTH
    vec4 opaque = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, depth, 1.0);
    #else
    vec4 opaque = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    #endif
    opaquePosition = opaque.xyz / opaque.w;
    return max(0.0, length(opaquePosition) - length(position));
}

vec3 _flw_forwardWaterReflection(vec3 position, vec3 view, vec3 normal) {
    vec3 reflection = texelFetch(colortex15, ivec2(1, 0), 0).rgb;
    #ifdef FLW_FORWARD_OVERWORLD
    vec3 sun = texelFetch(colortex15, ivec2(0, 0), 0).rgb;
    vec3 direction = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
    vec3 worldPosition = mat3(gbufferModelViewInverse) * position + gbufferModelViewInverse[3].xyz;
    vec3 halfVector = normalize(view + direction);
    reflection += sun * _flw_forwardShadow(worldPosition, normal)
    * pow(max(dot(normal, halfVector), 0.0), 256.0);
    #endif
    #ifdef FLW_FORWARD_ITERATION
    reflection *= 0.31830988618;
    #endif
    return reflection;
}

vec4 _flw_forwardWaterTransport(vec3 transmission, vec3 scattering, vec3 reflection, float fresnel) {
    // Scalar opacity stays bounded; native channel gain belongs in the RGB transfer.
    float scalarTransmission = min(1.0, max(transmission.r, max(transmission.g, transmission.b)));
    // An opaque medium has no transmitted background to tint.
    if (scalarTransmission > 0.0) _flw_forwardLogTint = log(transmission / scalarTransmission);
    float alpha = 1.0 - (1.0 - fresnel) * scalarTransmission;
    return alpha > 0.0 ? vec4((scattering * (1.0 - fresnel) + reflection * fresnel) / alpha, alpha) : vec4(0.0);
}

vec3 _flw_forwardView(vec3 position) {
    return normalize(mat3(gbufferModelViewInverse) * -position);
}

float _flw_forwardFresnel(vec3 view, inout vec3 normal) {
    normal = normalize(normal);
    if (dot(normal, view) < 0.0) normal = -normal;
    return 0.02 + 0.98 * pow(1.0 - max(dot(normal, view), 0.0), 5.0);
}

vec4 _flw_forwardShade(vec4 albedo, vec3 viewPosition, vec3 normal, vec2 light,
        float smoothness, float emission, bool water) {
    vec3 color = _flw_forwardLinear(albedo.rgb);
    float alpha = clamp(albedo.a, 0.0, 1.0);
    vec3 view = _flw_forwardView(viewPosition);
    float fresnel = _flw_forwardFresnel(view, normal);
    if (water) {
        #ifdef FLW_FORWARD_SEUS
        #ifdef FLW_FORWARD_GFME
        color = TransformInputColor(exp(-GetWaterAbsorption() * 4.0));
        #else
        color = TransformInputColor(GetWaterFogColor());
        #endif
        #endif
        vec3 opaque;
        float thickness = _flw_forwardWaterThickness(viewPosition, opaque);
        alpha = clamp(max(fresnel, 1.0 - dot(_flw_forwardAbsorption(thickness), vec3(0.2126, 0.7152, 0.0722))), 0.0, 1.0);
    }
    #ifdef FLW_FORWARD_UNLIT
    return vec4(color, alpha);
    #else
    vec3 sun = texelFetch(colortex15, ivec2(0, 0), 0).rgb;
    vec3 sky = texelFetch(colortex15, ivec2(1, 0), 0).rgb;
    vec3 direction = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
    vec3 worldPosition = mat3(gbufferModelViewInverse) * viewPosition + gbufferModelViewInverse[3].xyz;
    float ndl = max(dot(normal, direction), 0.0);
    vec3 shadow = _flw_forwardShadow(worldPosition, normal);
    vec3 torch = vec3(1.0, 0.65, 0.3);
    #ifdef FLW_FORWARD_SEUS
    torch = GetColorTorchlight();
    #endif
    vec3 diffuse = sky * (0.2 + 0.3 * max(normal.y, 0.0)) * light.y * light.y;
    diffuse += torch * pow(clamp(light.x, 0.0, 1.0), 3.0);
    diffuse += sun * ndl * shadow;
    #ifdef FLW_FORWARD_ITERATION
    // GetSunAndSkyIrradiance supplies irradiance, not reflected diffuse radiance.
    diffuse *= 0.31830988618;
    #endif
    vec3 halfVector = normalize(view + direction);
    float exponent = mix(8.0, 256.0, smoothness * smoothness);
    vec3 specular = sun * shadow * pow(max(dot(normal, halfVector), 0.0), exponent) * fresnel;
    return vec4(color * (diffuse + vec3(0.02) + emission * 8.0) + specular, alpha);
    #endif
}

#ifdef FLW_FORWARD_SUNDIAL
#if defined(VOLUMETRIC_LIGHT) && defined(FLW_FORWARD_OVERWORLD) && WATER_TYPE == 0
vec3 _flw_forwardSundialVolume(GbufferData gbufferData, vec3 worldPos, vec3 waterWorldPos,
        vec3 worldDir, float waterViewDepthNoLimit, vec3 stainedColor,
        out vec3 absorption) {
    float worldDistanceInv = inversesqrt(dot(worldPos, worldPos));
    vec3 waterWorldDir = worldDir;
    bool isTargetWater = true;
    float shadowLightFactor = 1.0;
    #ifdef LIGHT_LEAKING_FIX
    shadowLightFactor = clamp(gbufferData.lightmap.y * 10.0, 0.0, 1.0);
    #endif
    vec3 volumetricLight = vec3(0.0);
    vec4 solidColor = vec4(1.0);
    absorption = solidColor.rgb;
    return volumetricLight;
}
#endif

vec3 _flw_forwardSundialVolumeContribution() {
    #if defined(VOLUMETRIC_LIGHT) && defined(FLW_FORWARD_OVERWORLD) && WATER_TYPE == 0
    if (_flw_forwardWaterMaterial.materialID == MAT_WATER && isEyeInWater == 0) {
        vec3 worldPos = mat3(gbufferModelViewInverse) * _flw_forwardOpaquePosition;
        vec3 waterWorldPos = mat3(gbufferModelViewInverse) * _flw_forwardWaterPosition
        + gbufferModelViewInverse[3].xyz;
        vec3 absorption;
        vec3 light = _flw_forwardSundialVolume(_flw_forwardWaterMaterial, worldPos, waterWorldPos,
                normalize(worldPos), length(_flw_forwardWaterPosition), vec3(1.0 - _flw_forwardWaterFresnel), absorption);
        _flw_forwardLogTint += log(absorption);
        return _flw_forwardColor.rgb * (absorption - vec3(1.0)) + light / _flw_forwardColor.a;
    }
    #endif
    return vec3(0.0);
}

vec4 _flw_forwardSundialWater(GbufferData material, vec3 position) {
    _flw_forwardWaterPosition = position;
    vec3 view = _flw_forwardView(position);
    vec3 normal = mat3(gbufferModelViewInverse) * material.normal;
    float fresnel = _flw_forwardFresnel(view, normal);
    _flw_forwardWaterFresnel = fresnel;
    vec3 sky = texelFetch(colortex15, ivec2(1, 0), 0).rgb;
    _flw_forwardNativeSky = sky;
    vec3 scattering = vec3(0.0);
    vec3 transmission = vec3(1.0);
    if (isEyeInWater != 1) {
        float thickness = _flw_forwardWaterThickness(position, _flw_forwardOpaquePosition);
        transmission = exp(-waterAbsorptionBeta * thickness);
        scattering = waterFogScattering(-view, sky, thickness, material.lightmap.y);
    }
    return _flw_forwardWaterTransport(transmission, scattering,
            _flw_forwardWaterReflection(position, view, normal), fresnel);
}

vec4 _flw_forwardSundial(GbufferData material, vec3 position) {
    _flw_forwardWaterMaterial = material;
    bool water = material.materialID == MAT_WATER;
    #if WATER_TYPE == 0
    if (water) return _flw_forwardSundialWater(material, position);
    water = false;
    #endif
    #ifndef FLW_FORWARD_UNLIT
    if (!water && material.materialID != MAT_PARTICLE && material.emissive == 0.0) {
        vec3 view = _flw_forwardView(position);
        vec3 normal = mat3(gbufferModelViewInverse) * material.normal;
        _flw_forwardFresnel(view, normal);
        vec3 reflection;
        vec3 transmission = _flw_forwardSundialGlassTransmission(material, position, reflection);
        return _flw_forwardWaterTransport(transmission,
                reflection * _flw_forwardWaterReflection(position, view, normal), vec3(0.0), 0.0);
    }
    #endif
    return _flw_forwardShade(material.albedo, position, mat3(gbufferModelViewInverse) * material.normal,
            material.lightmap, material.smoothness, material.emissive, water);
}
#endif
#ifdef FLW_FORWARD_SEUS
vec4 _flw_forwardSeusWater(GBufferDataTransparent material, vec3 position) {
    _flw_forwardWaterSurface = true;
    _flw_forwardWaterPosition = position;
    vec3 view = _flw_forwardView(position);
    vec3 normal = mat3(gbufferModelViewInverse) * material.normal;
    float fresnel = _flw_forwardFresnel(view, normal);
    _flw_forwardWaterFresnel = fresnel;
    vec3 transmission = vec3(1.0);
    vec3 scattering = vec3(0.0);
    if (isEyeInWater != 1) {
        float thickness = _flw_forwardWaterThickness(position, _flw_forwardOpaquePosition);
        vec3 sun = texelFetch(colortex15, ivec2(0, 0), 0).rgb;
        vec3 sky = texelFetch(colortex15, ivec2(1, 0), 0).rgb;
        // The native fog is affine in incident radiance; zero illumination isolates extinction.
        UnderwaterFog(transmission, thickness, -view, vec3(0.0), vec3(0.0));
        UnderwaterFog(scattering, thickness, -view, sky, sun);
        // Native refraction fog uses a 100-unit linear buffer; the lighting resolve uses 120.
        scattering *= 1.2;
    }
    _flw_forwardWaterTransmission = transmission;
    return _flw_forwardWaterTransport(transmission, scattering,
            _flw_forwardWaterReflection(position, view, normal), fresnel);
}

void _flw_forwardSeusCameraFog(inout vec3 color, vec3 position) {
    vec3 worldDir = normalize(mat3(gbufferModelViewInverse) * position);
    vec3 sunDir = normalize(mat3(gbufferModelViewInverse) * sunPosition);
    #ifdef FLW_FORWARD_GFME
    LandAtmosphericScattering(color, position, worldDir, sunDir);
    #else
    LandAtmosphericScattering(color, position, normalize(position), worldDir, sunDir, 1.0);
    #endif
}

vec3 _flw_forwardSeusLightingContribution() {
    if (!_flw_forwardWaterSurface) return vec3(0.0);
    vec3 frontScatter = vec3(0.0);
    vec3 frontWhite = vec3(1.0);
    _flw_forwardSeusCameraFog(frontScatter, _flw_forwardWaterPosition);
    _flw_forwardSeusCameraFog(frontWhite, _flw_forwardWaterPosition);
    vec3 frontTransmission = frontWhite - frontScatter;
    vec3 delta = _flw_forwardColor.rgb * (frontTransmission - vec3(1.0));
    vec3 correction = frontScatter;
    if (max(_flw_forwardWaterTransmission.r, max(_flw_forwardWaterTransmission.g, _flw_forwardWaterTransmission.b)) > 0.0) {
        vec3 backScatter = vec3(0.0);
        vec3 backWhite = vec3(1.0);
        _flw_forwardSeusCameraFog(backScatter, _flw_forwardOpaquePosition);
        _flw_forwardSeusCameraFog(backWhite, _flw_forwardOpaquePosition);
        vec3 ratio = frontTransmission / (backWhite - backScatter);
        _flw_forwardLogTint += log(ratio);
        correction -= backScatter * _flw_forwardWaterTransmission * (1.0 - _flw_forwardWaterFresnel) * ratio;
    }
    // Replace the native air segment behind the interface with the water segment, retaining camera-side fog.
    return delta + correction / _flw_forwardColor.a;
}

vec4 _flw_forwardSeus(GBufferDataTransparent material, vec3 position) {
    bool water = int(material.materialID * 255.0) == 6;
    if (water && isEyeInWater != 1) return _flw_forwardSeusWater(material, position);
    #ifndef FLW_FORWARD_UNLIT
    vec3 view = _flw_forwardView(position);
    vec3 normal = mat3(gbufferModelViewInverse) * material.normal;
    float fresnel = _flw_forwardFresnel(view, normal);
    vec3 transmission = vec3(1.0);
    if (int(material.materialID * 255.0) == 7) {
        transmission = _flw_forwardSeusGlassTransmission(material);
        if (isEyeInWater == 0) {
            _flw_forwardWaterSurface = true;
            _flw_forwardWaterPosition = position;
            _flw_forwardWaterThickness(position, _flw_forwardOpaquePosition);
            _flw_forwardWaterFresnel = fresnel;
            _flw_forwardWaterTransmission = transmission;
        }
    }
    // Native transparent lighting uses texture pigment only for stained glass, not ice or other surfaces.
    return _flw_forwardWaterTransport(transmission, vec3(0.0),
            _flw_forwardWaterReflection(position, view, normal), fresnel);
    #else
    return _flw_forwardShade(material.albedo, position,
            mat3(gbufferModelViewInverse) * material.normal, material.mcLightmap, material.smoothness,
            0.0, water);
    #endif
}
vec4 _flw_forwardSeus(GBufferDataTransparent material, vec4 clip) {
    vec4 position = gbufferProjectionInverse * vec4(clip.xyz / clip.w, 1.0);
    return _flw_forwardSeus(material, position.xyz / position.w);
}
#endif

#ifdef FLW_FORWARD_ITERATION
vec4 _flw_forwardIterationWater(vec3 position, vec3 normal, float lightmap) {
    vec3 view = _flw_forwardView(position);
    float fresnel = _flw_forwardFresnel(view, normal);
    vec3 transmission = vec3(1.0);
    vec3 scattering = vec3(0.0);
    #ifdef FLW_FORWARD_OVERWORLD
    if (isEyeInWater != 1) {
        vec3 opaque;
        float thickness = _flw_forwardWaterThickness(position, opaque);
        vec3 direction = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
        vec3 shadowRay = refract(-direction, vec3(0.0, 1.0, 0.0), 1.0 / WATER_IOR);
        float vdot = dot(-view, shadowRay);
        _flw_forwardNativeSun = texelFetch(colortex15, ivec2(0, 0), 0).rgb;
        _flw_forwardNativeSky = vec3(0.0);
        _flw_forwardIterationFog(transmission, -view, thickness, normal, vdot, lightmap);
        _flw_forwardNativeSky = texelFetch(colortex15, ivec2(1, 0), 0).rgb;
        _flw_forwardIterationFog(scattering, -view, thickness, normal, vdot, lightmap);
    }
    #endif
    return _flw_forwardWaterTransport(transmission, scattering,
            _flw_forwardWaterReflection(position, view, normal), fresnel);
}

vec4 _flw_forwardIteration(vec4 albedo, vec3 position, vec3 normal, vec2 light,
        float smoothness, float emission, float kind, vec4 encoded) {
    vec3 viewPosition = (position - gbufferModelViewInverse[3].xyz) * mat3(gbufferModelViewInverse);
    bool water = kind == MATID_WATER;
    vec2 nativeLightmap = Unpack2xU8_from_U16(encoded.w);
    nativeLightmap.y = SkyLightmapCurve(nativeLightmap.y);
    #ifdef DIMENSION_END
    nativeLightmap.y = 1.0;
    #endif
    if (water && isEyeInWater != 1) return _flw_forwardIterationWater(viewPosition, normal, nativeLightmap.y);
    #ifndef FLW_FORWARD_UNLIT
    if (_flw_forwardIterationIsGlass(encoded)) return _flw_forwardIterationGlass(viewPosition, normal, encoded);
    #endif
    float radiance = 0.0;
    #ifdef LABPBR_EMISSIVENESS
    radiance = emission;
    #endif
    if (kind == MATID_STAINEDGLASS_EMISSIVE) radiance = max(radiance, 1.0);
    return _flw_forwardShade(albedo, viewPosition, normal, light, smoothness, radiance, water);
}
#endif

vec3 _flw_forwardLightingContribution() {
    #ifdef FLW_FORWARD_SUNDIAL
    return _flw_forwardSundialVolumeContribution();
    #else
    #ifdef FLW_FORWARD_SEUS
    return vec3(0.0);
    #else
    return vec3(0.0);
    #endif
    #endif
}
