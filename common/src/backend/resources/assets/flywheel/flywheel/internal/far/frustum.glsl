// Packed-plane sphere test; see FrameUniforms.writePackedFrustumPlanes and FrustumIntersection.testSphere.
bool _flw_farTestSphere(vec3 center, float radius) {
    bvec4 xyInside = greaterThanEqual(fma(flw_frustumPlanes.xyX, center.xxxx, fma(flw_frustumPlanes.xyY, center.yyyy,
            fma(flw_frustumPlanes.xyZ, center.zzzz, flw_frustumPlanes.xyW))), -radius.xxxx);
    bvec2 zInside = greaterThanEqual(fma(flw_frustumPlanes.zX, center.xx, fma(flw_frustumPlanes.zY, center.yy,
            fma(flw_frustumPlanes.zZ, center.zz, flw_frustumPlanes.zW))), -radius.xx);
    return all(xyInside) && all(zInside);
}
