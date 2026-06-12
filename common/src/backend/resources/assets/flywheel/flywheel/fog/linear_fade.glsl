// 26.2: apply_fog never touches alpha, so fade to transparent over the render-distance range (upstream LINEAR_FADE) -- multiplies alpha too, only reads on a TRANSPARENT material.
vec4 flw_fogFilter(vec4 color, float sphericalDistance, float cylindricalDistance) {
    return color * smoothstep(FogRenderDistanceEnd, FogRenderDistanceStart, cylindricalDistance);
}
