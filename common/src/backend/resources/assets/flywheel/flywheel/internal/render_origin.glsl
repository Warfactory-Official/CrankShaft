// Layout = RenderPassUniforms.RenderOriginUniform + DynamicLights.write.
#ifdef _FLW_RENDER_ORIGIN_BINDING
layout(std140, binding = _FLW_RENDER_ORIGIN_BINDING) uniform _FlwRenderOrigin {
#else
layout(std140) uniform _FlwRenderOrigin {
#endif
    ivec4 _flw_renderOrigin; // w: AO option
    uint _flw_constantAmbientLight;
    uint _flw_dynamicLightCount;
    uint _flw_dynamicSegmentCount;
    // Point-light bounds, render-origin relative; block centres outside see no point light.
    vec4 _flw_dynamicLightMin;
    vec4 _flw_dynamicLightMax;
    // Per 8-block cell key: first index (low 16) and count (high 16) of the lights reaching the cell.
    uvec4 _flw_dynamicLightCells[128];
    // xyz: render-origin relative; w: luminance.
    vec4 _flw_dynamicLights[128];
    // 16-bit light indices, 8 per uvec4.
    uvec4 _flw_dynamicLightIndices[432];
    // Per segment: a.xyz, luminance; b.xyz. Render-origin relative.
    vec4 _flw_dynamicSegments[32];
};

// = DynamicLights.key
uint _flw_dynamicLightKey(ivec3 cell) {
    uvec3 c = uvec3(cell);
    return ((c.x * 73856093u) ^ (c.y * 19349663u) ^ (c.z * 83492791u)) & 511u;
}

// LambDynamicLights falloff at the block centre, no occlusion: max(luminance - distance * 15 / 7.75), 0-15.
float _flw_dynamicBlockLight(vec3 pos) {
    ivec3 block = ivec3(floor(pos));
    vec3 centre = vec3(block) + 0.5;
    float result = 0.0;
    for (uint i = 0u; i < _flw_dynamicSegmentCount; i++) {
        vec4 a = _flw_dynamicSegments[2u * i];
        vec3 ab = _flw_dynamicSegments[2u * i + 1u].xyz - a.xyz;
        float t = clamp(dot(centre - a.xyz, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
        result = max(result, a.w - distance(centre, a.xyz + t * ab) * (15.0 / 7.75));
    }
    if (_flw_dynamicLightCount == 0u || any(lessThan(centre, _flw_dynamicLightMin.xyz))
            || any(greaterThan(centre, _flw_dynamicLightMax.xyz))) {
        return min(result, 15.0);
    }
    uint key = _flw_dynamicLightKey((block + _flw_renderOrigin.xyz) >> 3);
    uint cell = _flw_dynamicLightCells[key >> 2u][key & 3u];
    uint end = (cell & 0xFFFFu) + (cell >> 16u);
    for (uint i = cell & 0xFFFFu; i < end; i++) {
        uint light = (_flw_dynamicLightIndices[i >> 3u][(i >> 1u) & 3u] >> ((i & 1u) << 4u)) & 0xFFFFu;
        vec4 source = _flw_dynamicLights[light];
        result = max(result, source.w - distance(centre, source.xyz) * (15.0 / 7.75));
    }
    return min(result, 15.0);
}
