// 26.2: apply_fog + Fog* uniforms come from the #moj_import <minecraft:fog.glsl> the assembler prepends.
vec4 flw_fogFilter(vec4 color, float sphericalDistance, float cylindricalDistance) {
    return apply_fog(color, sphericalDistance, cylindricalDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
