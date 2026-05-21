layout(std140) uniform _FlwFogUniforms {
    vec4 flw_fogColor;
    vec2 flw_fogRange;
    int flw_fogShape;
    // 1.12.2: vanilla switches GL_FOG_MODE to EXP (by density) underwater, in lava, and in cloud
    // fog, leaving flw_fogRange stale. 0 = LINEAR (smoothstep over flw_fogRange), 1 = EXP.
    int flw_fogMode;
    float flw_fogDensity;
};

// Fog blend amount in [0, 1] (0 = clear, 1 = fully fogged). Shared by every fog filter so the
// vanilla LINEAR/EXP split applies uniformly to instanced and replayed chunk geometry.
float flw_fogFactor(float distance) {
    if (flw_fogMode == 1) {
        return 1.0 - exp(-flw_fogDensity * distance);
    }
    if (distance <= flw_fogRange.x) {
        return 0.0;
    }
    return distance < flw_fogRange.y ? smoothstep(flw_fogRange.x, flw_fogRange.y, distance) : 1.0;
}
