vec4 flw_fogFilter(vec4 color) {
    return color * (1.0 - flw_fogFactor(flw_distance));
}
