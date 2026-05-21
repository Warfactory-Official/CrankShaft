vec4 flw_fogFilter(vec4 color) {
    float fog = flw_fogFactor(flw_distance);
    return vec4(mix(color.rgb, flw_fogColor.rgb, fog * flw_fogColor.a), color.a);
}
