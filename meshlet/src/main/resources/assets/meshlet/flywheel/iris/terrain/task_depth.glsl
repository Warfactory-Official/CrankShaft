uniform sampler2D _flw_taskDepth;

bool _flw_boundsVisible(uvec4 test) {
    int level = int(test.x >> 2u);
    ivec4 bounds = ivec4(test.y & 0xffffu, test.y >> 16u, test.z & 0xffffu, test.z >> 16u);
    float farthest = min(min(texelFetch(_flw_taskDepth, bounds.xy, level).r,
                             texelFetch(_flw_taskDepth, bounds.zy, level).r),
                         min(texelFetch(_flw_taskDepth, bounds.xw, level).r,
                             texelFetch(_flw_taskDepth, bounds.zw, level).r));
    return uintBitsToFloat(test.w) + 1e-5 >= farthest;
}
